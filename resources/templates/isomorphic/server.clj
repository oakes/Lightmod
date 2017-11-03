(ns [[name]].server
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [not-found]]
            [[[name]].common :as common]
            [rum.core :as rum]))

; stores the people
(defonce people (atom []))

; runs when any request is received
(defn handler [{:keys [uri request-method] :as request}]
  (or ; if the request is for the people API
      (when (= uri "/people")
        (case request-method
          :get {:status 200
                :body (pr-str @people)}
          :post (do
                  (->> request :body .bytes slurp
                       edn/read-string (swap! people conj))
                  {:status 200})))
      ; if the request is a static file
      (let [file (io/file (System/getProperty "user.dir") (str "." uri))]
        (when (.isFile file)
          ; if it's index.html, use rum to render the
          ; react view and embed it in the page
          ; along with the initial state
          (if (= (.getName file) "index.html")
            {:status 200
             :body (-> (slurp file)
                       (str/replace "[[content]]" (rum/render-html (common/app people nil)))
                       (str/replace "[[initial-state]]" (pr-str @people)))}
            {:status 200
             :body file})))
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

