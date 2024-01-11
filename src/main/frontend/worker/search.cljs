(ns frontend.worker.search
  "Full-text and fuzzy search"
  (:require [clojure.string :as string]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            ["fuse.js" :as fuse]
            [goog.object :as gobj]
            [datascript.core :as d]
            [frontend.search.fuzzy :as fuzzy]
            [frontend.worker.util :as util]))

(defonce db-version-prefix "logseq_db_")
(defn db-based-graph?
  [s]
  (boolean
   (and (string? s)
        (string/starts-with? s db-version-prefix))))

;; TODO: use sqlite for fuzzy search
(defonce indices (atom nil))

(defn- add-blocks-fts-triggers!
  "Table bindings of blocks tables and the blocks FTS virtual tables"
  [db]
  (let [triggers [;; add
                  "CREATE TRIGGER IF NOT EXISTS blocks_ad AFTER DELETE ON blocks
                  BEGIN
                      DELETE from blocks_fts where id = old.id;
                  END;"
                  ;; insert
                  "CREATE TRIGGER IF NOT EXISTS blocks_ai AFTER INSERT ON blocks
                  BEGIN
                      INSERT INTO blocks_fts (id, content, page)
                      VALUES (new.id, new.content, new.page);
                  END;"
                  ;; update
                  "CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE ON blocks
                  BEGIN
                      DELETE from blocks_fts where id = old.id;
                      INSERT INTO blocks_fts (id, content, page)
                      VALUES (new.id, new.content, new.page);
                  END;"]]
    (doseq [trigger triggers]
      (.exec db trigger))))

(defn- create-blocks-table!
  [db]
  ;; id -> block uuid, page -> page uuid
  (.exec db "CREATE TABLE IF NOT EXISTS blocks (
                        id TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        page TEXT)"))

(defn- create-blocks-fts-table!
  [db]
  (.exec db "CREATE VIRTUAL TABLE IF NOT EXISTS blocks_fts USING fts5(id, content, page)"))

(defn create-tables-and-triggers!
  "Open a SQLite db for search index"
  [db]
  (try
    (create-blocks-table! db)
    (create-blocks-fts-table! db)
    (add-blocks-fts-triggers! db)
    (catch :default e
      (prn "Failed to create tables and triggers")
      (js/console.error e)
      ;; FIXME:
      ;; (try
      ;;   ;; unlink db
      ;;   (catch :default e
      ;;     (js/console.error "cannot unlink search db:" e)))
      )))

(defn- clj-list->sql
  "Turn clojure list into SQL list
   '(1 2 3 4)
   ->
   \"('1','2','3','4')\""
  [ids]
  (str "(" (->> (map (fn [id] (str "'" id "'")) ids)
                (string/join ", ")) ")"))

(defn upsert-blocks!
  [^Object db blocks]
  (.transaction db (fn [tx]
                     (doseq [item blocks]
                       (.exec tx #js {:sql "INSERT INTO blocks (id, content, page) VALUES ($id, $content, $page) ON CONFLICT (id) DO UPDATE SET (content, page) = ($content, $page)"
                                      :bind #js {:$id (.-id item)
                                                 :$content (.-content item)
                                                 :$page (.-page item)}})))))

(defn delete-blocks!
  [db ids]
  (let [sql (str "DELETE from blocks WHERE id IN " (clj-list->sql ids))]
    (.exec db sql)))

(defonce max-snippet-length 250)

(defn- snippet-by
  [content length]
  (str (subs content 0 length) (when (> (count content) max-snippet-length) "...")))

(defn- get-snippet-result
  [snippet]
  (let [;; Cut snippet to limited size chars for non-matched results
        flag-highlight "$pfts_2lqh>$ "
        snippet (if (string/includes? snippet flag-highlight)
                  snippet
                  (snippet-by snippet max-snippet-length))]
    snippet))

(defn- search-blocks-aux
  [db sql input page limit]
  (try
    (p/let [result (if page
                     (.exec db #js {:sql sql
                                    :bind #js [input page limit]
                                    :rowMode "array"})
                     (.exec db #js {:sql sql
                                    :bind #js [input limit]
                                    :rowMode "array"}))
            blocks (bean/->clj result)]
      (map (fn [block]
             (update block 3 get-snippet-result)) blocks))
    (catch :default e
      (prn :debug "Search blocks failed: ")
      (js/console.error e))))

(defn- get-match-input
  [q]
  (let [match-input (-> q
                        (string/replace " and " " AND ")
                        (string/replace " & " " AND ")
                        (string/replace " or " " OR ")
                        (string/replace " | " " OR ")
                        (string/replace " not " " NOT "))]
    (if (not= q match-input)
      (string/replace match-input "," "")
      (str "\"" match-input "\""))))

(defn search-blocks
  ":page - the page to specifically search on"
  [db q {:keys [limit page]}]
  (when-not (string/blank? q)
    (p/let [match-input (get-match-input q)
            non-match-input (str "%" (string/replace q #"\s+" "%") "%")
            limit  (or limit 20)
            ;; https://www.sqlite.org/fts5.html#the_highlight_function
            ;; the 2nd column in blocks_fts (content)
            ;; pfts_2lqh is a key for retrieval
            ;; highlight and snippet only works for some matching with high rank
            snippet-aux "snippet(blocks_fts, 1, ' $pfts_2lqh>$ ', ' $<pfts_2lqh$ ', '...', 32)"
            select (str "select id, page, content, " snippet-aux " from blocks_fts where ")
            pg-sql (if page "page = ? and" "")
            match-sql (str select
                           pg-sql
                           " content match ? order by rank limit ?")
            non-match-sql (str select
                               pg-sql
                               " content like ? limit ?")
            matched-result (search-blocks-aux db match-sql match-input page limit)
            non-match-result (search-blocks-aux db non-match-sql non-match-input page limit)
            all-result (->> (concat matched-result non-match-result)
                            (map (fn [[id _content page snippet]]
                                   {:uuid id
                                    :content snippet
                                    :page page})))]
      (->>
       all-result
       (util/distinct-by :uuid)
       (take limit)))))

(defn truncate-table!
  [db]
  (.exec db "delete from blocks")
  (.exec db "delete from blocks_fts"))

(defn- sanitize
  [content]
  (some-> content
          (util/search-normalize true)))

(defn- property-value-when-closed
  "Returns property value if the given entity is type 'closed value' or nil"
  [ent]
  (when (contains? (:block/type ent) "closed value")
    (get-in ent [:block/schema :value])))

(defn get-by-parent-&-left
  [db parent-id left-id]
  (when (and parent-id left-id)
    (let [lefts (:block/_left (d/entity db left-id))]
      (some (fn [node] (when (and (= parent-id (:db/id (:block/parent node)))
                                  (not= parent-id (:db/id node)))
                         node)) lefts))))

(defn- get-db-properties-str
  "Similar to db-pu/readable-properties but with a focus on making property values searchable"
  [db properties]
  (->> properties
       (map
        (fn [[k v]]
          (let [values
                (->> (if (set? v) v #{v})
                     (map (fn [val]
                            (if (uuid? val)
                              (let [e (d/entity db [:block/uuid val])
                                    value (or
                                           ;; closed value
                                           (property-value-when-closed e)
                                           ;; page
                                           (:block/original-name e)
                                           ;; block generated by template
                                           (and
                                            (get-in e [:block/metadata :created-from-template])
                                            (:block/content e))
                                           ;; first child
                                           (let [parent-id (:db/id e)]
                                             (:block/content (get-by-parent-&-left db parent-id parent-id))))]
                                value)
                              val)))
                     (remove string/blank?))]
            (when (seq values)
              (str (:block/original-name (d/entity db [:block/uuid k]))
                   ": "
                   (string/join "; " values))))))
       (remove nil?)
       (string/join ";; ")))

(defn whiteboard-page?
  "Given a page name or a page object, check if it is a whiteboard page"
  [db page]
  (cond
    (string? page)
    (let [page (d/entity db [:block/name page])]
      (or
       (= (:block/type page) "whiteboard")
       (contains? (set (:block/type page)) "whiteboard")))

    (seq page)
    (contains? (set (:block/type page)) "whiteboard")

    :else false))

(defn block->index
  "Convert a block to the index for searching"
  [repo db {:block/keys [name uuid page content properties format]
            :as block}]
  (let [page? (some? name)
        block? (nil? name)
        db-based? (db-based-graph? repo)]
    (when-not (or
               (and page? name (whiteboard-page? db name))
               (and block? (> (count content) 10000))
               (and (empty? properties)
                    (or (and block? (string/blank? content))
                        (and db-based? page?))))        ; empty page or block
      (let [content (if block?
                      content
                      ;; File based page content
                      (if db-based?
                        ""            ; empty page content
                        (some-> (:block/file (d/entity db (:db/id block))) :file/content)))
            content' (if (and db-based? (seq properties))
                       (str content (when (not= content "") "\n") (get-db-properties-str db properties))
                       content)]
        (when-not (string/blank? content')
          {:id (str uuid)
           :page (str (:block/uuid page))
           :content (sanitize content')
           :format format})))))

(defn get-single-block-contents [db id]
  (let [e (d/entity db [:block/uuid id])]
    (when-not (and (nil? (:block/name e))
                   (string/blank? (:block/content e))) ; empty block
      {:db/id (:db/id e)
       :block/name (:block/name e)
       :block/uuid id
       :block/page (:db/id (:block/page e))
       :block/content (:block/content e)
       :block/format (:block/format e)
       :block/properties (:block/properties e)})))

(defn get-all-block-contents
  [db]
  (when db
    (->> (d/datoms db :avet :block/uuid)
         (map :v)
         (map #(get-single-block-contents db %))
         (remove nil?))))

(defn build-blocks-indice
  [repo db]
  (->> (get-all-block-contents db)
       (map #(block->index repo db %))
       (remove nil?)
       (bean/->js)))

(defn original-page-name->index
  [p]
  (when p
    {:name (util/search-normalize p true)
     :original-name p}))

(defn- safe-subs
  ([s start]
   (let [c (count s)]
     (safe-subs s start c)))
  ([s start end]
   (let [c (count s)]
     (subs s (min c start) (min c end)))))

(defn- hidden-page?
  [page]
  (when page
    (if (string? page)
      (and (string/starts-with? page "$$$")
           (util/uuid-string? (safe-subs page 3)))
      (contains? (set (:block/type page)) "hidden"))))

(defn get-all-pages
  [db]
  (->>
   (d/q
    '[:find [?page-original-name ...]
      :where
      [?page :block/name ?page-name]
      [(get-else $ ?page :block/original-name ?page-name) ?page-original-name]]
    db)
   (remove hidden-page?)))

(defn build-page-indice
  "Build a page title indice from scratch.
   Incremental page title indice is implemented in frontend.search.sync-search-indice!
   Rename from the page indice since 10.25.2022, since this is only used for page title search.
   From now on, page indice is talking about page content search."
  [repo db]
  (let [pages (->> (get-all-pages db)
                   (remove string/blank?)
                   (map original-page-name->index)
                   (bean/->js))
        indice (fuse. pages
                      (clj->js {:keys ["name"]
                                :shouldSort true
                                :tokenize true
                                :minMatchCharLength 1}))]
    (swap! indices assoc-in [repo :pages] indice)
    indice))

(defn- get-blocks-from-datoms-impl
  [repo {:keys [db-after db-before]} datoms]
  (when (seq datoms)
    (let [blocks-to-add-set (->> (filter :added datoms)
                                 (map :e)
                                 (set))
          blocks-to-remove-set (->> (remove :added datoms)
                                    (filter #(= :block/uuid (:a %)))
                                    (map :e)
                                    (set))
          blocks-to-add-set' (if (and (db-based-graph? repo) (seq blocks-to-add-set))
                               (->> blocks-to-add-set
                                    (mapcat (fn [id] (map :db/id (:block/_refs (d/entity db-after id)))))
                                    (concat blocks-to-add-set)
                                    set)
                               blocks-to-add-set)]
      {:blocks-to-remove     (->>
                              (map #(d/entity db-before %) blocks-to-remove-set)
                              (remove nil?)
                              (remove hidden-page?))
       :blocks-to-add        (->>
                              (map #(d/entity db-after %) blocks-to-add-set')
                              (remove nil?)
                              (remove hidden-page?))})))

(defn- get-direct-blocks-and-pages
  [repo tx-report]
  (let [data (:tx-data tx-report)
        datoms (filter
                (fn [datom]
                  ;; Capture any direct change on page display title, page ref or block content
                  (contains? #{:block/uuid :block/name :block/original-name :block/content :block/properties :block/schema} (:a datom)))
                data)]
    (when (seq datoms)
      (get-blocks-from-datoms-impl repo tx-report datoms))))

(defn sync-search-indice
  [repo tx-report]
  (let [{:keys [blocks-to-add blocks-to-remove]} (get-direct-blocks-and-pages repo tx-report)]
    ;; update page title indice
    (let [pages-to-add (filter :block/name blocks-to-add)
          pages-to-remove (filter :block/name blocks-to-remove)]
      (when (or (seq pages-to-add) (seq pages-to-remove))
        (swap! indices update-in [repo :pages]
               (fn [indice]
                 (when indice
                   (doseq [page-entity pages-to-remove]
                     (.remove indice
                              (fn [page]
                                (= (:block/name page-entity)
                                   (util/safe-page-name-sanity-lc (gobj/get page "original-name"))))))
                   (doseq [page pages-to-add]
                     (.add indice (bean/->js (original-page-name->index
                                              (or (:block/original-name page)
                                                  (:block/name page))))))
                   indice)))))

    ;; update block indice
    (when (or (seq blocks-to-add) (seq blocks-to-remove))
      (let [blocks-to-add (remove nil? (map #(block->index repo (:db-after tx-report) %) blocks-to-add))
            blocks-to-remove (set (map (comp str :block/uuid) blocks-to-remove))]
        (bean/->js
         {:blocks-to-remove-set blocks-to-remove
          :blocks-to-add        blocks-to-add})))))

(defn exact-matched?
  "Check if two strings points toward same search result"
  [q match]
  (when (and (string? q) (string? match))
    (boolean
     (reduce
      (fn [coll char]
        (let [coll' (drop-while #(not= char %) coll)]
          (if (seq coll')
            (rest coll')
            (reduced false))))
      (seq (util/search-normalize match true))
      (seq (util/search-normalize q true))))))

(defn page-search
  "Return a list of page names that match the query"
  [repo db q limit]
  (when repo
    (let [q (util/search-normalize q true)
          q (fuzzy/clean-str q)
          q (if (= \# (first q)) (subs q 1) q)]
      (when-not (string/blank? q)
        (let [indice (or (get-in @indices [repo :pages])
                         (build-page-indice repo db))
              result (->> (.search indice q (clj->js {:limit limit}))
                          (bean/->clj))]
          (->> result
               (util/distinct-by (fn [i] (string/trim (get-in i [:item :name]))))
               (map
                (fn [{:keys [item]}]
                  (:original-name item)))
               (remove nil?)
               (map string/trim)
               (distinct)
               (filter (fn [original-name]
                         (exact-matched? q original-name)))
               bean/->js))))))