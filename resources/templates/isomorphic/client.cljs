(ns [[name]].client
  (:require [cljs.reader :refer [read-string]]
            [rum.core :as rum]
            [[[name]].common :as common])
  (:import goog.net.XhrIo))

; stores the people
(defonce people (atom []))

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

; sets the people to the server's initial state
(reset! people
  (-> (.querySelector js/document "#initial-state")
      .-textContent
      read-string))

; tells rum to begin rendering
(rum/mount (common/app people on-submit)
  (.querySelector js/document "#app"))

