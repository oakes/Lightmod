(ns [[name]].server
  (:require [clojure.java.io :as io]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [not-found]])
  (:gen-class))

; runs when any request is received
(defn handler [{:keys [uri] :as request}]
  (or ; if the request is a static file
      (let [file (io/file (System/getProperty "user.dir") (str "." uri))]
        (when (.isFile file)
          {:status 200
           :body file}))
      ; otherwise, send a 404
      (not-found "Page not found")))

; runs when the server starts
(defn -main [& args]
  (-> #'handler
      (wrap-content-type)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-reload)
      (wrap-resource "[[dir]]")
      (run-server {:port 0})))

