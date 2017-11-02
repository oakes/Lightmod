(ns {{name}}.server
  (:require [clojure.java.io :as io]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [not-found]]))

(defn handler [{:keys [uri] :as request}]
  (let [uri (if (.startsWith uri "/") (subs uri 1) uri)
        file (io/file (System/getProperty "user.dir") uri)]
    (if (.isFile file)
      {:status 200
       :body (slurp file)}
      (not-found ""))))

(defn -main [& args]
  (-> #'handler
      (wrap-reload)
      (wrap-resource "{{dir}}")
      (wrap-content-type)
      (run-jetty {:port 0 :join? false})))

