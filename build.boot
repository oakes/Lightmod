(set-env!
  :source-paths #{"src/clj" "src/cljs"}
  :resource-paths #{"resources"}
  :dependencies '[[org.clojure/test.check "0.9.0" :scope "test"]
                  [adzerk/boot-cljs "2.1.4" :scope "test"]
                  [adzerk/boot-reload "0.5.2"]
                  [paren-soup "2.9.3" :scope "test"]
                  [mistakes-were-made "1.7.3" :scope "test"]
                  [cljsjs/codemirror "5.24.0-1" :scope "test"]
                  [hawk "0.2.11"]
                  [eval-soup "1.2.3"]
                  [org.clojure/tools.namespace "0.3.0-alpha4"]
                  [org.clojure/tools.reader "1.1.0"]
                  [nightcode "2.5.1"
                   :exclusions [leiningen
                                play-cljs/lein-template
                                org.eclipse.jgit/org.eclipse.jgit]]
                  [dynadoc "1.1.2"]
                  ; make sure the rest of this is in the boot template
                  ; so exported projects have the same deps
                  [org.clojure/clojure "1.9.0-RC2"]
                  [org.clojure/clojurescript "1.9.946"]
                  [org.clojure/core.async "0.3.443"]
                  [org.clojure/data.json "0.2.6"]
                  [org.clojure/tools.cli "0.3.5"]
                  [http-kit "2.2.0"]
                  [ring "1.6.2"]
                  [reagent "0.8.0-alpha2"]
                  [cljs-react-material-ui "0.2.48"]
                  [rum "0.10.8"]
                  [play-cljs "0.11.0"]
                  [compojure "1.6.0"]
                  [com.rpl/specter "1.0.4"]
                  [com.taoensso/sente "1.11.0"]
                  [org.clojure/java.jdbc "0.7.3"]
                  [com.h2database/h2 "1.4.196"]
                  [honeysql "0.9.1"]]
  :repositories (conj (get-env :repositories)
                  ["clojars" {:url "https://clojars.org/repo/"
                              :username (System/getenv "CLOJARS_USER")
                              :password (System/getenv "CLOJARS_PASS")}]))

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[clojure.java.io :as io])

(task-options!
  sift {:include #{#"\.jar$"}}
  pom {:project 'lightmod
       :version "1.0.6-SNAPSHOT"
       :description "An all-in-one tool for full stack Clojure"
       :url "https://github.com/oakes/Lightmod"
       :license {"Public Domain" "http://unlicense.org/UNLICENSE"}}
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
    (aot)
    (with-pass-thru _
      (require
        '[clojure.spec.test.alpha :refer [instrument]]
        '[lightmod.core :refer [dev-main]])
      ((resolve 'instrument))
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
  (comp (aot) (pom) (uber :exclude jar-exclusions) (jar) (sift) (target)))

(deftask build-cljs []
  (set-env! :dependencies
    (conj (get-env :dependencies)
      '[javax.xml.bind/jaxb-api "2.3.0" :scope "test"]))
  (comp
    (cljs :optimizations :advanced)
    (target)
    (with-pass-thru _
      (.renameTo (io/file "target/public/paren-soup.js") (io/file "resources/public/paren-soup.js"))
      (.renameTo (io/file "target/public/codemirror.js") (io/file "resources/public/codemirror.js"))
      (.renameTo (io/file "target/public/loading.js") (io/file "resources/public/loading.js")))))

(deftask local []
  (set-env! :resource-paths #{"src/clj" "src/cljs"})
  (comp (pom) (jar) (install)))

(deftask deploy []
  (set-env! :resource-paths #{"src/clj" "src/cljs"})
  (comp (pom) (jar) (push)))

