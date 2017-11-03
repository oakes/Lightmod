(ns [[name]].client
  (:require [cljs.reader :refer [read-string]]
            [reagent.core :as r])
  (:import goog.net.XhrIo))

; stores the people
(def people (r/atom []))

; gets the people list
(defn get-people []
  (.send XhrIo
    "/people"
    (fn [e]
      (reset! people
        (-> e .-target .getResponseText read-string)))
    "GET"))

; runs when the form is submitted
(defn on-submit [e]
  (.preventDefault e)
  (let [input (.querySelector js/document "#input")
        first-name (.querySelector js/document "#first")
        last-name (.querySelector js/document "#last")]
    (.send XhrIo
      "/people"
      (fn [e]
        (set! (.-value first-name) "")
        (set! (.-value last-name) "")
        (get-people))
      "POST"
      (pr-str {:first_name (.-value first-name)
               :last_name (.-value last-name)}))))

; reagent component to be rendered
(defn content []
  [:form {:on-submit on-submit
          :style {:margin "10px"}}
   [:div {:style {:display "flex"}}
    [:input {:id "first"
             :type "text"
             :placeholder "First name"
             :style {:flex 1}}]
    [:input {:id "last"
             :type "text"
             :placeholder "Last name"
             :style {:flex 1}}]
    [:button {:type "submit"}
     "Submit"]]
   [:table {:style {:overflow "auto"}}
    (into [:tbody]
      (for [person @people]
        [:tr
         [:td (:first_name person)]
         [:td (:last_name person)]]))]])

; tells reagent to begin rendering
(r/render-component [content]
  (.querySelector js/document "#app"))

; runs the initial query
(set! (.-onload js/window)
  (get-people))

