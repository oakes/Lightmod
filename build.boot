(set-env!
  :dependencies '[[adzerk/boot-cljs "2.1.4" :scope "test"]
                  [seancorfield/boot-tools-deps "0.1.4" :scope "test"]
                  [com.google.guava/guava "21.0" :scope "test"]
                  [orchestra "2017.11.12-1"]]
  :repositories (conj (get-env :repositories)
                  ["clojars" {:url "https://clojars.org/repo/"
                              :username (System/getenv "CLOJARS_USER")
                              :password (System/getenv "CLOJARS_PASS")}]))

(require
  '[orchestra.spec.test :refer [instrument]]
  '[clojure.edn :as edn]
  '[adzerk.boot-cljs :refer [cljs]]
  '[clojure.java.io :as io]
  '[boot-tools-deps.core :refer [deps]])

(task-options!
  sift {:include #{#"\.jar$"}}
  pom {:project 'lightmod
       :version "1.1.5"
       :description "An all-in-one tool for full stack Clojure"
       :url "https://github.com/oakes/Lightmod"
       :license {"Public Domain" "http://unlicense.org/UNLICENSE"}
       :dependencies (->> "deps.edn"
                          slurp
                          edn/read-string
                          :deps
                          (reduce
                            (fn [deps [artifact info]]
                              (if-let [version (:mvn/version info)]
                                (conj deps
                                  (transduce cat conj [artifact version]
                                    (select-keys info [:scope :exclusions])))
                                deps))
                            []))}
  push {:repo "clojars"}
  aot {:namespace '#{lightmod.core}}
  jar {:main 'lightmod.core
       :manifest {"Description" "An all-in-one tool for full stack Clojure"
                  "Url" "https://github.com/oakes/Lightmod"}
       :file "project.jar"})

(deftask run []
  (set-env! :dependencies
    (conj (get-env :dependencies)
      '[javax.xml.bind/jaxb-api "2.3.0" :scope "test"]))
  (comp
    (deps)
    (aot)
    (with-pass-thru _
      (require '[lightmod.core :refer [dev-main]])
      (instrument)
      ((resolve 'dev-main)))))

(def jar-exclusions
  ;; the standard exclusions don't work on windows,
  ;; because we need to use backslashes
  (conj boot.pod/standard-jar-exclusions
    #"(?i)^META-INF\\[^\\]*\.(MF|SF|RSA|DSA)$"
    #"(?i)^META-INF\\INDEX.LIST$"))

(deftask build [_ package bool "Build for javapackager."]
  (set-env! :dependencies
    (conj (get-env :dependencies)
      ; if building for javapackager, don't include jaxb in the final jar
      (if package
        '[javax.xml.bind/jaxb-api "2.3.0" :scope "test"]
        '[javax.xml.bind/jaxb-api "2.3.0"])))
  (comp (deps) (aot) (pom) (uber :exclude jar-exclusions) (jar) (sift) (target)))

(deftask build-cljs []
  (set-env! :dependencies
    (conj (get-env :dependencies)
      '[javax.xml.bind/jaxb-api "2.3.0" :scope "test"]))
  (comp
    (deps :aliases [:cljs])
    (cljs)
    (target)
    (with-pass-thru _
      (.renameTo (io/file "target/dynadoc-extend/main.js") (io/file "resources/dynadoc-extend/main.js"))
      (.renameTo (io/file "target/public/paren-soup.js") (io/file "resources/public/paren-soup.js"))
      (.renameTo (io/file "target/public/codemirror.js") (io/file "resources/public/codemirror.js"))
      (.renameTo (io/file "target/public/loading.js") (io/file "resources/public/loading.js")))))

(deftask local []
  (set-env! :resource-paths #{"src/clj" "src/cljs"})
  (comp (pom) (jar) (install)))

(deftask deploy []
  (set-env! :resource-paths #{"src/clj" "src/cljs"})
  (comp (pom) (jar) (push)))

