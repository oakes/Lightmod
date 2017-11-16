(ns [[name]].server
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [not-found]])
  (:gen-class))

; defines basic parameters for the database
(def db-spec
  {:dbtype "h2"
   :dbname "./[[dir]]/main"
   :user "admin"
   :password ""})

; creates the people table
(defn create-tables []
  (jdbc/db-do-commands db-spec
    (jdbc/create-table-ddl :people
      [[:id :identity]
       [:first_name :varchar]
       [:last_name :varchar]]
      {:conditional? true})))

; selects all people from the table
(defn get-people []
  (jdbc/with-db-connection [db-conn db-spec]
    (jdbc/query db-conn "SELECT * FROM people")))

; adds an entry to the people table
(defn insert-person [person]
  (jdbc/with-db-connection [db-conn db-spec]
    (jdbc/insert! db-conn :people person)))

; runs when any request is received
(defn handler [{:keys [uri request-method] :as request}]
  (or ; if the request is for the people API
      (when (= uri "/people")
        (case request-method
          :get {:status 200
                :body (pr-str (get-people))}
          :post (do
                  (-> request :body .bytes slurp
                      edn/read-string insert-person)
                  {:status 200})))
      ; if the request is a static file
      (let [file (io/file (System/getProperty "user.dir") (str "." uri))]
        (when (.isFile file)
          {:status 200
           :body file}))
      ; otherwise, send a 404
      (not-found "Page not found")))

; runs when the server starts
(defn -main [& args]
  (create-tables)
  (-> #'handler
      (wrap-content-type)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-reload)
      (wrap-resource "[[dir]]")
      (run-server {:port 0})))

