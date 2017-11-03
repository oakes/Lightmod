(ns {{name}}.server
  (:require [clojure.java.io :as io]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [not-found]]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]))

; sets up various things for the chat server
(defonce socket (sente/make-channel-socket! (get-sch-adapter) {}))
(def ring-ajax-get-or-ws-handshake (:ajax-get-or-ws-handshake-fn socket))
(def ring-ajax-post (:ajax-post-fn socket))
(def ch-chsk (:ch-recv socket))
(def chsk-send! (:send-fn socket))
(def connected-uids (:connected-uids socket))

; runs when a message is received
(defn event-msg-handler
  [{:keys [id ?data ?reply-fn]}]
  (case id
    :chat/message
    (doseq [uid (:any @connected-uids)]
      (chsk-send! uid [id ?data]))
    nil))

; runs when any request is received
(defn handler [{:keys [uri request-method] :as request}]
  (or ; if the request is to the chat server
      (when (= uri "/chat")
        (case request-method
          :get (ring-ajax-get-or-ws-handshake request)
          :post (ring-ajax-post request)))
      ; if the request is an existing file
      (let [file (io/file (System/getProperty "user.dir") (str "." uri))]
        (when (.isFile file)
          {:status 200
           :body (slurp file)}))
      ; otherwise, send a 404
      (not-found "Page not found")))

; runs when the server starts
(defn -main [& args]
  (sente/start-server-chsk-router! ch-chsk event-msg-handler)
  (-> #'handler
      (wrap-content-type)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-reload)
      (wrap-resource "{{dir}}")
      (run-server {:port 0})))

