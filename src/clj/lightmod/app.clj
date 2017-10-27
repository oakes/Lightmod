(ns lightmod.app
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nightcode.state :refer [pref-state runtime-state]]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.utils :as u]
            [cljs.build.api :refer [build]]
            [hawk.core :as hawk]
            [lightmod.reload :as lr])
  (:import [javafx.application Platform]
           [javafx.scene.control Button ContentDisplay Label]
           [javafx.scene.image ImageView]
           [javafx.event EventHandler]
           [javafx.fxml FXMLLoader]))

(defn dir-pane [f]
  (let [pane (FXMLLoader/load (io/resource "dir.fxml"))]
    (shortcuts/add-tooltips! pane [:#up :#new_file :#open_in_file_browser :#close])
    (doseq [file (.listFiles f)
            :when (and  (-> file .getName (.startsWith ".") not)
                        (-> file .getName (not= "main.js")))]
      (-> (.lookup pane "#filegrid")
          .getContent
          .getChildren
          (.add (doto (if-let [icon (u/get-icon-path file)]
                        (Button. "" (doto (Label. (.getName file)
                                            (doto (ImageView. icon)
                                              (.setFitWidth 90)
                                              (.setPreserveRatio true)))
                                      (.setContentDisplay ContentDisplay/TOP)))
                        (Button. (.getName file)))
                  (.setPrefWidth 150)
                  (.setPrefHeight 150)
                  (.setOnAction (reify EventHandler
                                  (handle [this event]
                                    (swap! pref-state assoc :selection (.getCanonicalPath file)))))))))
    pane))

(defn set-selection-listener! [scene]
  (add-watch pref-state :selection-changed
    (fn [_ _ _ {:keys [selection]}]
      (when selection
        (let [file (io/file selection)]
          (when-let [pane (or (when (.isDirectory file)
                                (dir-pane file))
                              (get-in @runtime-state [:editor-panes selection])
                              (when-let [new-editor (e/editor-pane pref-state runtime-state file)]
                                (swap! runtime-state update :editor-panes assoc selection new-editor)
                                new-editor))]
            (let [editors (.lookup scene "#editors")]
              (shortcuts/hide-tooltips! editors)
              (doto (.getChildren editors)
                (.clear)
                (.add pane)))
            (.setDisable (.lookup scene "#up") (= selection (:current-project @runtime-state)))
            (.setDisable (.lookup scene "#close") (.isDirectory file))
            (Platform/runLater
              (fn []
                (some-> (.lookup pane "#webview") .requestFocus)))))))))

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

(declare compile-cljs!)

(defn start-server! [scene dir port]
  (when-let [{:keys [server reload-stop-fn reload-file-watcher]}
             (get-in @runtime-state [:projects dir])]
    (.stop server)
    (reload-stop-fn)
    (hawk/stop! reload-file-watcher))
  (load-file (.getCanonicalPath (io/file dir "server.clj")))
  (let [-main (resolve (symbol (path->ns dir "server") "-main"))
        server (-main "--port" (str port))
        port (-> server .getConnectors (aget 0) .getLocalPort)
        reload-stop-fn (lr/start-reload-server! dir)
        reload-port (-> reload-stop-fn meta :local-port)
        out-dir (.getCanonicalPath (io/file dir ".out"))]
    (spit (io/file dir ".out/reload-port.txt") (str reload-port))
    (swap! runtime-state assoc-in [:projects dir]
      {:server server
       :reload-stop-fn reload-stop-fn
       :clients #{}
       :editor-file-watcher (or (get-in @runtime-state [:projects dir :editor-file-watcher])
                                (e/create-file-watcher dir runtime-state))
       :reload-file-watcher
       (hawk/watch! [{:paths [dir]
                      :handler (fn [ctx {:keys [kind file]}]
                                 (when (and (= kind :modify)
                                            (some #(-> file .getName (.endsWith %)) [".clj" ".cljc"]))
                                   (load-file (.getCanonicalPath file))
                                   (lr/send-message! dir {:type :visual-clj}))
                                 (if (and (some #(-> file .getName (.endsWith %)) [".cljs" ".cljc"])
                                          (not (u/parent-path? out-dir (.getCanonicalPath file))))
                                   (do
                                     (compile-cljs! scene dir)
                                     (lr/send-message! dir {:type :visual}))
                                   (lr/reload-file! dir file))
                                 ctx)}])})
    (-> (.lookup scene "#app")
        .getEngine
        (.load (str "http://localhost:" port "/index.html")))))

(defn compile-cljs! [scene dir]
  (System/setProperty "user.dir" dir)
  (build dir
    {:output-to (.getCanonicalPath (io/file dir "main.js"))
     :output-dir (.getCanonicalPath (io/file dir ".out"))
     :main (path->ns dir "client")
     :asset-path ".out"
     :preloads '[lightmod.init]
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
                 "cljsjs/react-dom/common/react-dom.ext.js"])}))

(defn init-app! [scene dir]
  (compile-cljs! scene dir)
  (start-server! scene dir 0))

