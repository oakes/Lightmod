(ns {{name}}.client
  (:require [reagent.core :as r]
            [taoensso.sente :as sente]))

; sets up various things for the chat server
(defonce socket (sente/make-channel-socket! "/chat" {:type :auto}))
(def ch-chsk (:ch-recv socket))
(def chsk-send! (:send-fn socket))

; store the messages
(defonce messages (r/atom []))

; runs when a message is received
(defn event-msg-handler
  [{:keys [id ?data event]}]
  (case id
    :chsk/recv
    (case (first ?data)
      :chat/message
      (swap! messages conj (second ?data)))
    nil))

; reagent component that displays the messages
(defn content []
  [:form {:on-submit (fn [e]
                       (.preventDefault e)
                       (let [input (.querySelector js/document "#input")]
                         (chsk-send! [:chat/message (.-value input)])
                         (set! (.-value input) "")))}
   (into [:div {:style {:height "50%"
                        :border "1px solid"
                        :overflow "auto"}}]
     (for [msg @messages]
       [:div msg]))
   [:input {:id "input"
            :type "text"
            :placeholder "Type a message and hit enter"
            :style {:width "100%"}}]])

; tells reagent to begin rendering
(r/render-component [content]
  (.querySelector js/document "#app"))

; connects to the server
(sente/start-client-chsk-router! ch-chsk event-msg-handler)

