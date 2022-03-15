(ns frontend.components.hierarchy
  (:require [clojure.string :as string]
            [frontend.components.block :as block]
            [frontend.db.model :as db-model]
            [frontend.state :as state]
            [frontend.text :as text]
            [frontend.ui :as ui]
            [frontend.util.hierarchy :as hierarchy]
            [medley.core :as medley]
            [rum.core :as rum]
            [frontend.util :as util]))

;; (defn get-relation
;;   [page]
;;   (when-let [page (or (text/get-nested-page-name page) page)]
;;     (when (or (text/namespace-page? page)
;;               (:block/_namespace (db/entity [:block/name (util/page-name-sanity-lc page)])))
;;       (let [repo (state/get-current-repo)
;;             namespace-pages (db/get-namespace-pages repo page)
;;             parent-routes (db-model/get-page-namespace-routes repo page)
;;             pages (->> (concat namespace-pages parent-routes)
;;                        (distinct)
;;                        (sort-by :block/name)
;;                        (map (fn [page]
;;                               (or (:block/original-name page) (:block/name page))))
;;                        (map #(string/split % "/")))
;;             page-namespace (db-model/get-page-namespace repo page)
;;             page-namespace (util/get-page-original-name page-namespace)]
;;         (js-debugger)
;;         (cond
;;           ; Only check pages if the current title is a 
;;           (and (seq pages) (string/includes? page "/"))
;;           pages
;; 
;;           page-namespace
;;           [(string/split page-namespace "/")]
;; 
;;           :else
;;           nil)))))

; TODO: there are multiple source-of-truths for establishing a page hierarchy. :block/namespace and parsing out
; the title. We should really only rely on one or the other. Considering the namespace property is explicit, I
; lean towards that one, however parsing out the title is more flexible and easier to work with when renaming
; and rebuilding the hierarchy. Further, relying on :block/namespace would also allow us to support aliases reliably
; (see #4123). We can also adapt the same flexibility when parsing out the new-title in page-handler/rename-page-aux
; so there's really no downsides that I can think of.
(defn get-relation
  "Accepts sanitized or unsanitized. Page is a the title of a page"
  [page]
  (when-let [page (or (text/get-nested-page-name page) page)]
    (cond
      (text/namespace-page? page)
      (let 
       [repo (state/get-current-repo)

        ; TODO (#4129): Query by alias, but what happens when an alias changes?
        ; parent-alias-routes (db-model/get-page-namespace-alias-routes repo page)
        parent-routes (db-model/get-page-namespace-routes repo page)
        parent-page (db-model/get-page-by-name repo (hierarchy/get-hierarchy-page-name page))
        ; children-pages (db-model/get-namespace-hierarchy repo page)
        pages (->> (concat parent-routes [parent-page] #_children-pages)
                   (distinct)
                   (sort-by :block/name)
                   (map (fn [page]
                          (or (:block/original-name page) (:block/name page))))
                   (map #(string/split % "/")))]
       (println (str "pages: " pages))
       ; return pages
       pages)

      :else
      nil)))

(rum/defc structures
  [page]
  (let [namespaces (get-relation page)]
    (when (seq namespaces)
      [:div.page-hierachy.mt-6
       (ui/foldable
        [:h2.font-bold.opacity-30 "Hierarchy"]
        [:ul.namespaces {:style {:margin "12px 24px"}}
         (for [namespace namespaces]
           [:li.my-2
            (->>
             (for [[idx page] (medley/indexed namespace)]
               (when (and (string? page) page)
                 (let [full-page (->> (take (inc idx) namespace)
                                      (string/join "/"))]
                   (block/page-reference false
                                         full-page
                                         {}
                                         page))))
             (interpose [:span.mx-2.opacity-30 "/"]))])]
        {:default-collapsed? false
         :title-trigger? true})])))
