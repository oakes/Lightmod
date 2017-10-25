(ns lightmod.game
  (:require [clojure.java.io :as io]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.util.response :refer [redirect not-found]]
            [ring.util.request :refer [body-string]]
            [nightcode.state :refer [runtime-state]]
            [cljs.build.api :refer [build]]))

(defn handler [request]
  (or (case (:uri request)
        "/" (redirect "/index.html")
        "/main.js" (redirect ".out/main.js")
        nil)
      (let [uri (:uri request)
            uri (if (.startsWith uri "/") (subs uri 1) uri)
            file (io/file (:project-dir @runtime-state) uri)]
        (when (.exists file)
          {:status 200
           :body (slurp file)}))
      (not-found "")))

(defn start-web-server! []
  (-> handler
      (wrap-content-type)
      (run-jetty {:port 0 :join? false})
      .getConnectors
      (aget 0)
      .getLocalPort))

(defn init-game! [scene]
  (let [dir (:project-dir @runtime-state)]
    (build dir
      {:output-to (.getCanonicalPath (io/file dir ".out" "main.js"))
       :output-dir (.getCanonicalPath (io/file dir ".out"))
       :main (str (.getName (io/file dir)) ".core")
       :asset-path ".out"}))
  (let [port (start-web-server!)
        game (.lookup scene "#game")
        engine (.getEngine game)]
    (.load engine (str "http://localhost:" port "/"))))

