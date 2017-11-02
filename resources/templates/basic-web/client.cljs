(ns {{name}}.client
  (:require [reagent.core :as r]))

(def clicks (r/atom 0))

(defn content []
  [:div
   [:p "You clicked " @clicks " times"]
   [:button {:on-click (fn []
                         (swap! clicks inc))}
    "Click me"]])

(r/render-component [content]
  (.querySelector js/document "#app"))

