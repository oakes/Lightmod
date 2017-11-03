(ns [[name]].client
  (:require [reagent.core :as r]))

; stores the click count
(defonce clicks (r/atom 0))

; reagent component to be rendered
(defn content []
  [:div
   [:p "You clicked " @clicks " times"]
   [:button {:on-click (fn []
                         (swap! clicks inc))}
    "Click me"]])

; tells reagent to begin rendering
(r/render-component [content]
  (.querySelector js/document "#app"))

