(ns [[name]].common
  (:require [rum.core :as rum]))

(rum/defc app < rum/reactive [people on-submit]
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
       (for [person (rum/react people)]
         [:tr
          [:td (:first_name person)]
          [:td (:last_name person)]]))]])

