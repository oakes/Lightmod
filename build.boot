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
     :source-paths (set paths)
     :resource-paths (set paths)}))

(let [{:keys [source-paths resource-paths dependencies]} (read-deps-edn [])]
  (set-env!
    :source-paths source-paths
    :resource-paths resource-paths
    :dependencies (into '[[adzerk/boot-cljs "2.1.5" :scope "test"]
                          [com.google.guava/guava "21.0" :scope "test"]
                          [orchestra "2018.12.06-2" :scope "test"]
                          [org.openjfx/javafx-graphics "11.0.2" :classifier "win"]
                          [org.openjfx/javafx-graphics "11.0.2" :classifier "linux"]
                          [org.openjfx/javafx-graphics "11.0.2" :classifier "mac"]
                          [org.openjfx/javafx-web "11.0.2" :classifier "win"]
                          [org.openjfx/javafx-web "11.0.2" :classifier "linux"]
                          [org.openjfx/javafx-web "11.0.2" :classifier "mac"]]
                        dependencies)
    :repositories (conj (get-env :repositories)
                    ["clojars" {:url "https://clojars.org/repo/"
                                :username (System/getenv "CLOJARS_USER")
                                :password (System/getenv "CLOJARS_PASS")}])))

(require
  '[orchestra.spec.test :refer [instrument]]
  '[adzerk.boot-cljs :refer [cljs]]
  '[clojure.java.io :as io])

(task-options!
  sift {:include #{#"\.jar$"}}
  pom {:project 'lightmod
       :version "1.3.0"
       :description "An all-in-one tool for full stack Clojure"
       :url "https://github.com/oakes/Lightmod"
       :license {"Public Domain" "http://unlicense.org/UNLICENSE"}}
  push {:repo "clojars"}
  aot {:namespace '#{lightmod.start
                     lightmod.core}}
  jar {:main 'lightmod.start
       :manifest {"Description" "An all-in-one tool for full stack Clojure"
                  "Url" "https://github.com/oakes/Lightmod"}
       :file "project.jar"})

(deftask run []
  (set-env! :dependencies #(conj % '[javax.xml.bind/jaxb-api "2.3.0" :scope "test"]))
  (comp
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
  (set-env!
    :dependencies
    (fn [deps]
      (conj deps
        ; if building for javapackager, don't include jaxb in the final jar
        (if package
          '[javax.xml.bind/jaxb-api "2.3.0" :scope "test"]
          '[javax.xml.bind/jaxb-api "2.3.0"]))))
  (comp (aot) (pom) (uber :exclude jar-exclusions) (jar) (sift) (target)))

(deftask build-cljs []
  (set-env!
    :resource-paths #(conj % "dev-resources")
    :dependencies
    (fn [deps]
      (-> deps
          set
          (into (:dependencies (read-deps-edn [:cljs])))
          (conj '[javax.xml.bind/jaxb-api "2.3.0" :scope "test"]))))
  (comp
    (cljs)
    (target)
    (with-pass-thru _
      (io/copy (io/file "target/dynadoc-extend/main.js") (io/file "resources/dynadoc-extend/main.js"))
      (io/copy (io/file "target/public/paren-soup.js") (io/file "resources/public/paren-soup.js"))
      (io/copy (io/file "target/public/codemirror.js") (io/file "resources/public/codemirror.js"))
      (io/copy (io/file "target/public/loading.js") (io/file "resources/public/loading.js")))))

(deftask local []
  (set-env! :resource-paths #{"src/clj" "src/cljs"})
  (comp (pom) (jar) (install)))

(deftask deploy []
  (set-env! :resource-paths #{"src/clj" "src/cljs"})
  (comp (pom) (jar) (push)))

