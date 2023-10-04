(ns frontend.components.dnd
  (:require [rum.core :as rum]
            [cljs-bean.core :as bean]
            ["@dnd-kit/sortable" :refer [useSortable arrayMove SortableContext sortableKeyboardCoordinates verticalListSortingStrategy] :as sortable]
            ["@dnd-kit/utilities" :refer [CSS]]
            ["@dnd-kit/core" :refer [DndContext closestCenter KeyboardSensor PointerSensor useSensor useSensors DragOverlay]]
            ["react-dom" :refer [createPortal]]
            [frontend.rum :as r]
            [frontend.util :as util]))

(def dnd-context (r/adapt-class DndContext))
(def sortable-context (r/adapt-class SortableContext))
(def drag-overlay (r/adapt-class DragOverlay))

(rum/defc sortable-item
  [props children]
  (let [sortable (useSortable #js {:id (:id props)})
        attributes (.-attributes sortable)
        listeners (.-listeners sortable)
        set-node-ref (.-setNodeRef sortable)
        transform (.-transform sortable)
        transition (.-transition sortable)
        style #js {:transform ((.-toString (.-Transform CSS)) transform)
                   :transition transition}]
    [:div (merge
           {:ref set-node-ref
            :style style}
           (bean/->clj attributes)
           (bean/->clj listeners))
     children]))

(rum/defc items
  [col {:keys [on-drag-end parent-node]}]
  (let [ids (mapv :id col)
        items (bean/->js ids)
        id->item (zipmap ids col)
        [items set-items] (rum/use-state items)
        [_active-id set-active-id] (rum/use-state nil)
        sensors (useSensors (useSensor PointerSensor (bean/->js {:activationConstraint {:distance 8}}))
                            (useSensor KeyboardSensor #js {:coordinateGetter sortableKeyboardCoordinates}))
        dnd-opts {:sensors sensors
                  :collisionDetection closestCenter
                  :onDragStart (fn [event]
                                 (set-active-id (.-id (.-active event))))
                  :onDragEnd (fn [event]
                               (let [active-id (.-id (.-active event))
                                     over-id (.-id (.-over event))]
                                 (when-not (= active-id over-id)
                                   (let [old-index (.indexOf ids active-id)
                                         new-index (.indexOf ids over-id)
                                         new-items (arrayMove items old-index new-index)]
                                     (set-items new-items)
                                     (when (fn? on-drag-end)
                                       (let [new-values (mapv (fn [id] (:value (id->item id))) new-items)]
                                         (prn :debug :new-values new-values)
                                         (on-drag-end new-values)))))
                                 (set-active-id nil)))}
        sortable-opts {:items items
                       :strategy verticalListSortingStrategy}
        children (for [item col]
                   (let [id (str (:id item))]
                     (rum/with-key
                       (sortable-item {:key id
                                       :id id}
                                      (:content item))
                       id)))
        children' (if parent-node
                    [parent-node children]
                    children)]
    (dnd-context
     dnd-opts
     (sortable-context sortable-opts children')
     ;; (createPortal
     ;;  (drag-overlay
     ;;   (when active-id
     ;;     (sortable-item {:key active-id
     ;;                     :id active-id}
     ;;                    (:content (first (filter (fn [{:keys [id]}] (= id active-id)) items))))))
     ;;  js/document.body)
     )))