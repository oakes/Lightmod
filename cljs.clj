(require
  '[cljs.build.api :as api]
  '[leiningen.core.project :as p :refer [defproject]]
  '[leiningen.clean :refer [clean]])

(defn read-project-clj []
  (p/ensure-dynamic-classloader)
  (-> "project.clj" load-file var-get))

(-> (read-project-clj)
    p/init-project
    clean)

(println "Building paren-soup.js")
(api/build "src" {:main          'nightcode.paren-soup
                  :optimizations :advanced
                  :output-to     "resources/public/paren-soup.js"
                  :output-dir    "target/public/paren-soup.out"})

(println "Building codemirror.js")
(api/build "src" {:main          'nightcode.codemirror
                  :optimizations :advanced
                  :output-to     "resources/public/codemirror.js"
                  :output-dir    "target/public/codemirror.out"})

(println "Building loading.js")
(api/build "src" {:main          'lightmod.loading
                  :optimizations :advanced
                  :output-to     "resources/public/loading.js"
                  :output-dir    "target/public/loading.out"})

(println "Building dynadoc-extend/main.js")
(api/build "src" {:main          'lightmod.dynadoc
                  :optimizations :simple
                  :output-to     "resources/dynadoc-extend/main.js"
                  :output-dir    "target/dynadoc-extend/main.out"})

