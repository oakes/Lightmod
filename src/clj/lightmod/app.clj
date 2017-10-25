(ns lightmod.app
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nightcode.state :refer [pref-state runtime-state]]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [cljs.build.api :refer [build]])
  (:import [javafx.application Platform]
           [javafx.fxml FXMLLoader]))

(defn update-editor! [scene]
  (when-let [path (:selection @pref-state)]
    (let [file (io/file path)]
      (when-let [pane (or (when (.isDirectory file)
                            (doto (FXMLLoader/load (io/resource "dir.fxml"))
                              (shortcuts/add-tooltips! [:#up :#new_file :#open_in_file_browser :#close])))
                          (get-in @runtime-state [:editor-panes path])
                          (when-let [new-editor (e/editor-pane pref-state runtime-state file)]
                            (swap! runtime-state update :editor-panes assoc path new-editor)
                            new-editor))]
        (let [editors (.lookup scene "#editors")]
          (shortcuts/hide-tooltips! editors)
          (doto (.getChildren editors)
            (.clear)
            (.add pane)))
        (.setDisable (.lookup scene "#up") (= path (:current-project @runtime-state)))
        (.setDisable (.lookup scene "#close") (.isDirectory file))
        (Platform/runLater
          (fn []
            (some-> (.lookup pane "#webview") .requestFocus)))))))

(defn copy-from-resources! [from to]
  (let [dest (io/file to ".out" from)]
    (.mkdirs (.getParentFile dest))
    (spit dest (slurp (io/resource from)))
    (str ".out/" from)))

(defn sanitize-name [s ns?]
  (-> s
      str/lower-case
      (str/replace
        (if ns?
          #"[ _]"
          #"[ \-]")
        (if ns?
          "-"
          "_"))
      (str/replace #"[^a-z0-9\-]" "")))

(defn path->ns [path leaf-name]
  (-> path io/file .getName (sanitize-name true) (str "." leaf-name)))

(defn start-server! [scene project-dir port]
  (when-let [server (get-in @runtime-state [:servers project-dir])]
    (.stop server))
  (load-file (.getCanonicalPath (io/file project-dir "server.clj")))
  (let [-main (resolve (symbol (path->ns project-dir "server") "-main"))
        server (-main "--port" (str port))
        port (-> server .getConnectors (aget 0) .getLocalPort)]
    (swap! runtime-state assoc-in [:servers project-dir] server)
    (-> (.lookup scene "#app")
        .getEngine
        (.load (str "http://localhost:" port "/")))))

(defn init-app! [scene dir]
  (System/setProperty "user.dir" dir)
  (build dir
    {:output-to (.getCanonicalPath (io/file dir "main.js"))
     :output-dir (.getCanonicalPath (io/file dir ".out"))
     :main (path->ns dir "client")
     :asset-path ".out"
     :foreign-libs (mapv #(update % :file copy-from-resources! dir)
                     [{:file "js/p5.js"
                       :provides ["p5.core"]}
                      {:file "js/p5.tiledmap.js"
                       :provides ["p5.tiled-map"]
                       :requires ["p5.core"]}
                      {:file "cljsjs/react/development/react.inc.js"
                       :provides ["react" "cljsjs.react"]
                       :file-min ".out/cljsjs/react/production/react.min.inc.js"
                       :global-exports '{react React}}
                      {:file "cljsjs/create-react-class/development/create-react-class.inc.js"
                       :provides ["cljsjs.create-react-class" "create-react-class"]
                       :requires ["react"]
                       :file-min "cljsjs/create-react-class/production/create-react-class.min.inc.js"
                       :global-exports '{create-react-class createReactClass}}
                      {:file "cljsjs/react-dom/development/react-dom.inc.js"
                       :provides ["react-dom" "cljsjs.react.dom"]
                       :requires ["react"]
                       :file-min "cljsjs/react-dom/production/react-dom.min.inc.js"
                       :global-exports '{react-dom ReactDOM}}])
     :externs (mapv #(copy-from-resources! % dir)
                ["cljsjs/react/common/react.ext.js"
                 "cljsjs/create-react-class/common/create-react-class.ext.js"
                 "cljsjs/react-dom/common/react-dom.ext.js"])})
  (start-server! scene dir 0))

