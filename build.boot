(set-env!
  :source-paths #{"src/clj"}
  :resource-paths #{"resources"}
  :dependencies '[[org.clojure/test.check "0.9.0" :scope "test"]
                  [adzerk/boot-cljs "2.1.4" :scope "test"]
                  [adzerk/boot-reload "0.5.2"]
                  [org.clojure/clojurescript "1.9.946"]
                  [paren-soup "2.9.0" :scope "test"]
                  [mistakes-were-made "1.7.3" :scope "test"]
                  [cljsjs/codemirror "5.24.0-1" :scope "test"]
                  [http-kit "2.2.0"]
                  [hawk "0.2.11"]
                  [play-cljs "0.10.1"]
                  [reagent "0.7.0"]
                  [ring "1.6.2"]
                  [compojure "1.6.0"]
                  [org.clojure/core.async "0.3.443"]
                  [com.rpl/specter "1.0.4"]
                  [org.clojure/tools.cli "0.3.5"]
                  [org.clojure/clojure "1.9.0-beta2"]
                  [eval-soup "1.2.3"]
                  [javax.xml.bind/jaxb-api "2.3.0"] ; necessary for Java 9 compatibility
                  [nightcode "2.5.1"
                   :exclusions [leiningen
                                            play-cljs/lein-template
                                            org.eclipse.jgit/org.eclipse.jgit]]])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[clojure.java.io :as io])

(task-options!
  sift {:include #{#"\.jar$"}}
  pom {:project 'lightmod
       :version "1.0.0"}
  aot {:namespace '#{lightmod.core}}
  jar {:main 'lightmod.core
       :manifest {"Description" "A starter kit for full-stack Clojure(Script)"
                  "Url" "https://github.com/oakes/Lightmod"}
       :file "project.jar"})

(deftask run []
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

(deftask build []
  (comp (aot) (pom) (uber :exclude jar-exclusions) (jar) (sift) (target)))

(deftask build-cljs []
  (comp
    (cljs :optimizations :advanced)
    (target)
    (with-pass-thru _
      (.renameTo (io/file "target/public/paren-soup.js") (io/file "resources/public/paren-soup.js"))
      (.renameTo (io/file "target/public/codemirror.js") (io/file "resources/public/codemirror.js")))))

