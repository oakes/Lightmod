(require
  '[cljs.build.api :as api]
  '[leiningen.core.project :as p :refer [defproject]]
  '[leiningen.uberjar :refer [uberjar]]
  '[clojure.java.io :as io])

(defn read-project-clj []
  (p/ensure-dynamic-classloader)
  (-> "project.clj" load-file var-get))

(defn read-deps-edn [aliases-to-include]
  (let [{:keys [paths deps aliases]} (-> "deps.edn" slurp clojure.edn/read-string)
        deps (->> (select-keys aliases aliases-to-include)
                  vals
                  (mapcat :extra-deps)
                  (into deps)
                  (reduce
                    (fn [deps [artifact info]]
                      (if-let [version (:mvn/version info)]
                        (conj deps
                          (transduce cat conj [artifact version]
                            (select-keys info [:scope :exclusions])))
                        deps))
                    []))]
    {:dependencies deps
     :source-paths []
     :resource-paths paths}))

(def project (-> (read-project-clj)
                 (merge (read-deps-edn []))
                 p/init-project))

(defn delete-children-recursively! [f]
  (when (.isDirectory f)
    (doseq [f2 (.listFiles f)]
      (delete-children-recursively! f2)))
  (when (.exists f) (io/delete-file f)))

(def out-file "resources/[[dir]]/main.js")
(def out-dir "resources/[[dir]]/main.out")

(delete-children-recursively! (io/file out-dir))

(println "Building main.js")
(api/build "src" {:main          '[[name]].client
                  :optimizations :advanced
                  :output-to     out-file
                  :output-dir    out-dir
                  :infer-externs true})

(delete-children-recursively! (io/file out-dir))

(println "Building uberjar")
(uberjar project)
