(ns frontend.components.property
  "Block properties management."
  (:require [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.handler.property :as property-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.db :as db]
            [frontend.config :as config]
            [rum.core :as rum]
            [frontend.state :as state]
            [frontend.mixins :as mixins]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [goog.dom :as gdom]
            [frontend.search :as search]
            [frontend.components.search.highlight :as highlight]
            [frontend.components.svg :as svg]
            [frontend.modules.shortcut.core :as shortcut]
            [medley.core :as medley]
            [cljs-time.coerce :as tc]
            [frontend.date :as date]))

(rum/defcs property-config <
  rum/static
  (rum/local nil ::property-name)
  (rum/local nil ::property-schema)
  {:will-mount (fn [state]
                 (let [[repo property] (:rum/args state)]
                   (reset! (::property-name state) (:block/original-name property))
                   (reset! (::property-schema state) (:block/schema property))
                   state))}
  [state repo property]
  (let [*property-name (::property-name state)
        *property-schema (::property-schema state)]
    [:div.property-configure
     [:h1.title "Configure property"]

     [:div.grid.gap-2.p-1
      [:div.grid.grid-cols-4.gap-1.items-center.leading-8
       [:label.cols-1 "Name:"]
       [:input.form-input
        {:on-change #(reset! *property-name (util/evalue %))
         :value @*property-name}]]

      [:div.grid.grid-cols-4.gap-1.leading-8
       [:label.cols-1 "Schema type:"]
       (let [schema-types (->> (keys property-handler/builtin-schema-types)
                               (map (comp string/capitalize name))
                               (map (fn [type]
                                      {:label type
                                       :value type
                                       :selected (= (keyword (string/lower-case type))
                                                    (:type @*property-schema))})))]
         (ui/select schema-types
           (fn [_e v]
             (let [type (keyword (string/lower-case v))]
               (swap! *property-schema assoc :type type)))))]

      (when-not (= (:type @*property-schema) :checkbox)
        [:div.grid.grid-cols-4.gap-1.items-center.leading-8
         [:label.cols-1 "Multiple values:"]
         (let [many? (boolean (= :many (:cardinality @*property-schema)))]
           (ui/checkbox {:checked many?
                         :on-change (fn []
                                      (swap! *property-schema assoc :cardinality (if many? :one :many)))}))])

      [:div
       (ui/button
         "Save"
         :on-click (fn []
                     (property-handler/update-property!
                      repo (:block/uuid property)
                      {:property-name @*property-name
                       :property-schema @*property-schema})
                     (state/close-modal!)))]

      (when config/dev?
        [:div {:style {:max-width 900}}
         [:hr]
         [:p "Debug data:"]
         [:code
          (str property)]])]]))

(rum/defc search-item-render
  [search-q content]
  [:div.font-medium
   (highlight/highlight-exact-query content search-q)])

(defn- exit-edit-property
  [*property-key *property-value]
  (when *property-key (reset! *property-key nil))
  (when *property-value (reset! *property-value nil))
  (property-handler/set-editing-new-property! nil))

(defn- add-property!
  [block *property-key *property-value]
  (let [repo (state/get-current-repo)]
    (when @*property-key
      (property-handler/add-property! repo block @*property-key @*property-value))
    (exit-edit-property *property-key *property-value)))

(rum/defc date-picker
  [block property value]
  (let [value' (when-not (string/blank? value)
                 (tc/to-local-date value))
        text (if value'
               (str value')
               "Pick a date")]
    [:a
     {:on-click (fn []
                  (state/set-modal!
                   #(ui/datepicker value' {:on-change (fn [_e date]
                                                        (let [repo (state/get-current-repo)]
                                                          (property-handler/add-property! repo block
                                                                                          (:block/name property)
                                                                                          date)
                                                          (exit-edit-property nil nil)
                                                          (state/close-modal!)))})))}
     [:span.inline-flex.items-center
      (ui/icon "calendar")
      [:span.ml-1 text]]]))

(defn- set-editing!
  [property editor-id dom-id v]
  (let [v (str v)
        cursor-range (if dom-id
                       (some-> (gdom/getElement dom-id) util/caret-range)
                       "")]
    (state/set-editing! editor-id v property cursor-range)))

(rum/defc property-scalar-value < rum/reactive
  [block property value {:keys [inline-text
                                editor-id dom-id
                                editor-box editor-args
                                new-item?]}]
  (let [multiple-values? (= :many (:cardinality (:block/schema property)))
        editing? (state/sub [:editor/editing? editor-id])]
    (when (or (not new-item?) editing?)
      (case (:type (:block/schema property))
       :date
       (date-picker block property value)

       :checkbox
       (ui/checkbox {:checked value
                     :on-change (fn [_e]
                                  (let [repo (state/get-current-repo)]
                                    (property-handler/add-property! repo block
                                                                    (:block/name property)
                                                                    (boolean (not value)))
                                    (exit-edit-property nil nil)))})

       ;; :others
       (if editing?
         [:div.flex.flex-1 (cond-> {}
                             multiple-values?
                             (assoc :class "property-value-content"))
          (editor-box editor-args editor-id {})]
         [:div.flex.flex-1
          (cond->
              {:id (or dom-id (random-uuid))
               :style {:min-height 24}
               :on-click (fn []
                           (set-editing! property editor-id dom-id value))}
            multiple-values?
            (assoc :class "property-value-content"))
          (when-not (string/blank? value)
            (inline-text {} :markdown (str value)))])))))

(rum/defc property-key-input
  [block *property-key *property-value *search?]
  [:input#add-property.form-input.simple-input.block.col-span-1.focus:outline-none
   {:placeholder "Property key"
    :value @*property-key
    :auto-focus true
    :on-change (fn [e]
                 (reset! *property-key (util/evalue e))
                 (reset! *search? true))
    :on-key-down (fn [e]
                   (case (util/ekey e)
                     "Escape"
                     (exit-edit-property *property-key *property-value)

                     (list "Tab" "Enter")
                     (let [k (util/evalue e)]
                       (when (= (util/ekey e) "Tab")
                         (util/stop e))
                       (reset! *property-key k)
                       (reset! *search? false)
                       (when (and @*property-key
                                  (nil? (db/entity [:block/name (util/page-name-sanity-lc @*property-key)])))
                         ;; new property
                         (add-property! block *property-key *property-value)
                         (when-let [property (db/entity [:block/name (util/page-name-sanity-lc k)])]
                           (let [editor-id (str "ls-property-" (:db/id property) "-" (:block/uuid property))]
                             (set-editing! property editor-id "" "")))))

                     nil))}])

(rum/defcs property-input < rum/reactive
  (rum/local true ::search?)
  shortcut/disable-all-shortcuts
  [state entity *property-key *property-value block-components-m]
  (let [*search? (::search? state)
        entity-properties (->> (keys (:block/properties entity))
                               (map #(:block/original-name (db/entity [:block/uuid %])))
                               (set))
        result (when-not (string/blank? @*property-key)
                 (->> (search/property-search @*property-key)
                      (remove entity-properties)))
        property (when @*property-key
                   (db/entity [:block/name (util/page-name-sanity-lc @*property-key)]))]
    [:div
     [:div.ls-property-add.grid.grid-cols-4.gap-1.flex.flex-row.items-center
      (property-key-input entity *property-key *property-value *search?)
      (when property
        (property-scalar-value entity property @*property-value block-components-m))

      [:a.close {:on-mouse-down #(exit-edit-property *property-key *property-value)}
       svg/close]]
     (when @*search?
       (ui/auto-complete
        result
        {:class "search-results"
         :on-chosen (fn [chosen]
                      (reset! *property-key chosen)
                      (reset! *search? false))
         :item-render #(search-item-render @*property-key %)}))]))

(rum/defcs new-property < rum/reactive
  (rum/local nil ::property-key)
  (rum/local nil ::property-value)
  (mixins/event-mixin
   (fn [state]
     (mixins/hide-when-esc-or-outside
      state
      :on-hide (fn []
                 (property-handler/set-editing-new-property! nil))
      :node (js/document.getElementById "edit-new-property")
      :outside? false)))
  [state repo block edit-input-id properties new-property? block-components-m]
  (let [*property-key (::property-key state)
        *property-value (::property-value state)]
    (cond
     new-property?
     [:div#edit-new-property
      (property-input block *property-key *property-value block-components-m)]

     (seq properties)
     [:a {:title "Add another property"
          :on-click (fn []
                      (property-handler/set-editing-new-property! edit-input-id)
                      (reset! *property-key nil)
                      (reset! *property-value nil))}
      [:div.block {:style {:height      20
                           :width       20}}
       [:a.add-button-link.block {:style {:margin-left -4}}
        (ui/icon "circle-plus")]]])))

(rum/defcs property-key < (rum/local false ::show-close?)
  [state block property]
  (let [repo (state/get-current-repo)
        *show-close? (::show-close? state)]
    [:div.relative
     {:on-mouse-over (fn [_] (reset! *show-close? true))
      :on-mouse-out (fn [_] (reset! *show-close? false))}
     [:a.mr-2
      {:title (str "Configure property: " (:block/original-name property))
       :on-click (fn [] (state/set-modal! #(property-config repo property)))}
      (:block/original-name property)]
     (when @*show-close?
       [:div.absolute.top-0.right-0
        [:a.fade-link.fade-in.py-2.px-1
         {:title "Remove this property"
          :on-click (fn [_e]
                      (property-handler/remove-property! repo block (:block/uuid property)))}
         (ui/icon "x")]])]))

(rum/defcs multiple-value-item < (rum/local false ::show-close?)
  [state entity property item {:keys [dom-id editor-id
                                      page-cp inline-text]
                               :as opts}]
  (let [*show-close? (::show-close? state)
        object? (= :object (:type (:block/schema property)))
        block (when object? (db/pull [:block/uuid item]))]
    [:div.flex.flex-1.flex-row {:on-mouse-over #(reset! *show-close? true)
                                :on-mouse-out  #(reset! *show-close? false)}
     (property-scalar-value entity property item opts)
     (when @*show-close?
       [:a.close.fade-in
        {:title "Delete this value"
         :on-mouse-down
         (fn []
           (property-handler/delete-property-value! (state/get-current-repo)
                                                    entity
                                                    (:block/uuid property)
                                                    item))}
        svg/close])]))

(rum/defcs property-value < rum/reactive
  [state block property value
   {:keys [inline-text editor-box page-cp]}]
  (let [k (:block/uuid property)
        v (get (:block/properties-text-values block)
               k
               (get (:block/properties block) k))
        dom-id (str "ls-property-" k)
        editor-id (str "ls-property-" (:db/id property) "-" k)

        schema (:block/schema property)
        multiple-values? (= :many (:cardinality schema))
        type (:type schema)
        editor-args {:block property
                     :parent-block block
                     :format :markdown}]
    (cond
      multiple-values?
      (let [v' (if (coll? v) v (when v [v]))
            v' (if (seq v') v' [""])
            v' (conj v' ::new-value-placeholder)            ; new one
            editor-id' (str editor-id (count v'))
            ]
        [:div.flex.flex-1.flex-col
         [:div.flex.flex-1.flex-col
          (for [[idx item] (medley/indexed v')]
            (let [dom-id' (str dom-id "-" idx)
                  editor-id' (str editor-id idx)]
              (multiple-value-item block property item
                                   {:dom-id dom-id'
                                    :editor-id editor-id'
                                    :editor-box editor-box
                                    :editor-args editor-args
                                    :page-cp page-cp
                                    :inline-text inline-text
                                    :new-item? (= item ::new-value-placeholder)})))

          (let [fv (first v')]
            (when (and fv
                       (or (and (string? fv) (not (string/blank? fv)))
                           (and (not (string? fv)) (some? fv))))
              [:div.rounded-sm.ml-1
               {:on-click (fn []
                            (set-editing! property (str editor-id (dec (count v'))) nil ""))}
               [:div.flex.flex-row
                [:div.block {:style {:height      20
                                     :width       20}}
                 [:a.add-button-link.block {:title "Add another value"
                                            :style {:margin-left -4}}
                  (ui/icon "circle-plus")]]]]))]])

      :else
      [:div.flex.flex-1.items-center.property-value-content
       (property-scalar-value block property value
                              {:editor-box editor-box
                               :editor-args editor-args
                               :inline-text inline-text
                               :editor-id editor-id
                               :dom-id dom-id})])))

(rum/defcs properties-area < rum/reactive
  [state block properties properties-text-values edit-input-id block-components-m]
  (let [repo (state/get-current-repo)
        new-property? (= edit-input-id (state/sub :ui/new-property-input-id))]
    (when (or (seq properties) new-property?)
      [:div.ls-properties-area
      (when (seq properties)
        [:div
         (for [[prop-uuid-or-built-in-prop v] properties]
           (let [v (get properties-text-values prop-uuid-or-built-in-prop v)]
             (if (uuid? prop-uuid-or-built-in-prop)
               (when-let [property (db/sub-block (:db/id (db/entity [:block/uuid prop-uuid-or-built-in-prop])))]
                 [:div.grid.grid-cols-4.gap-1
                  [:div.property-key.col-span-1
                   (property-key block property)]
                  [:div.property-value.col-span-3
                   (property-value block property v block-components-m)]])
               ;; TODO: built in properties should have UUID and corresponding schema
               ;; builtin
               [:div
                [:a.mr-2 (str prop-uuid-or-built-in-prop)]
                [:span v]])))])
       (new-property repo block edit-input-id properties new-property? block-components-m)])))