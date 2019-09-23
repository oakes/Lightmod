(ns lightmod.controller
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.state :refer [*pref-state *runtime-state]]
            [nightcode.utils :as u]
            [lightmod.app :as a]
            [lightmod.ui :as ui]
            [lightmod.utils :as lu])
  (:import [javafx.event ActionEvent]
           [javafx.scene.control Alert Alert$AlertType ButtonType TextInputDialog]
           [javafx.stage DirectoryChooser FileChooser StageStyle Window Modality]
           [javafx.application Platform]
           [javafx.scene Scene]
           [javafx.scene.input KeyEvent KeyCode])
  (:gen-class
   :methods [[onNewBasicApp [javafx.event.ActionEvent] void]
             [onNewChatApp [javafx.event.ActionEvent] void]
             [onNewDatabaseApp [javafx.event.ActionEvent] void]
             [onNewBasicGame [javafx.event.ActionEvent] void]
             [onNewMusic [javafx.event.ActionEvent] void]
             [onNewIsomorphicApp [javafx.event.ActionEvent] void]
             [onUp [javafx.event.ActionEvent] void]
             [onSave [javafx.event.ActionEvent] void]
             [onUndo [javafx.event.ActionEvent] void]
             [onRedo [javafx.event.ActionEvent] void]
             [onInstaRepl [javafx.event.ActionEvent] void]
             [onFind [javafx.scene.input.KeyEvent] void]
             [onClose [javafx.event.ActionEvent] void]
             [onDarkTheme [javafx.event.ActionEvent] void]
             [onLightTheme [javafx.event.ActionEvent] void]
             [onFontDec [javafx.event.ActionEvent] void]
             [onFontInc [javafx.event.ActionEvent] void]
             [onAutoSave [javafx.event.ActionEvent] void]
             [onNewFile [javafx.event.ActionEvent] void]
             [onOpenInFileBrowser [javafx.event.ActionEvent] void]
             [onOpenInWebBrowser [javafx.event.ActionEvent] void]
             [onRestart [javafx.event.ActionEvent] void]
             [onNewVersion [javafx.event.ActionEvent] void]
             [onExport [javafx.event.ActionEvent] void]
             [onOpenDocsInWebBrowser [javafx.event.ActionEvent] void]]))

(defn apply-template! [dir project-type project-name dir-name]
  (.mkdirs dir)
  (doseq [file-name (->> (str "templates/" (name project-type) "/files.edn")
                         io/resource
                         slurp
                         edn/read-string)
          :let [from (io/resource (str "templates/" (name project-type) "/" file-name))
                dest (io/file dir file-name)]]
    (if (-> file-name u/get-extension #{"clj" "cljs" "cljc" "edn"})
      (spit dest
        (-> (slurp from)
            (str/replace "[[name]]" project-name)
            (str/replace "[[dir]]" dir-name)))
      (io/copy (io/input-stream from) dest))))

; new project

(defn new-project! [^Scene scene project-type]
  (let [dialog (doto (TextInputDialog. (name project-type))
                 (.setTitle "New Project")
                 (.setHeaderText "Enter a name for your project.")
                 (.setGraphic nil)
                 (.initOwner (.getWindow scene))
                 (.initModality Modality/WINDOW_MODAL))]
    (when-let [project-name (some-> dialog .showAndWait (.orElse nil) u/sanitize-name)]
      (when-let [projects-dir (:projects-dir @*runtime-state)]
        (let [dir-name (str/replace project-name "-" "_")
              dir (io/file projects-dir dir-name)]
          (if (or (empty? project-name)
                  (-> project-name (.charAt 0) Character/isLetter not)
                  (.exists dir))
            (ui/alert! "The name must be unique and start with a letter.")
            (do
              (apply-template! dir project-type project-name dir-name)
              (-> scene (.lookup "#projects") .getTabs
                  (.add (ui/create-tab scene dir a/start-app! a/stop-app!))))))))))

(defn -onNewBasicApp [this ^ActionEvent event]
  (-> event .getSource .getScene (new-project! :basic)))

(defn -onNewChatApp [this ^ActionEvent event]
  (-> event .getSource .getScene (new-project! :chat)))

(defn -onNewDatabaseApp [this ^ActionEvent event]
  (-> event .getSource .getScene (new-project! :database)))

(defn -onNewBasicGame [this ^ActionEvent event]
  (-> event .getSource .getScene (new-project! :basic-game)))

(defn -onNewMusic [this ^ActionEvent event]
  (-> event .getSource .getScene (new-project! :music)))

(defn -onNewIsomorphicApp [this ^ActionEvent event]
  (-> event .getSource .getScene (new-project! :isomorphic)))

; remove

(defn should-remove? [^Scene scene ^String path]
  (let [paths-to-delete (->> @*runtime-state :editor-panes keys (filter #(u/parent-path? path %)))
        get-pane #(get-in @*runtime-state [:editor-panes %])
        get-engine #(-> % get-pane (.lookup "#webview") .getEngine)
        unsaved? #(-> % get-engine (.executeScript "isClean()") not)
        unsaved-paths (filter unsaved? paths-to-delete)]
    (or (empty? unsaved-paths)
        (->> (map #(-> % io/file .getName) unsaved-paths)
             (str/join \newline)
             (str "The below files are not saved. Proceed?" \newline \newline)
             (u/show-warning! scene "Unsaved Files")))))

; up

(defn up! [^Scene scene]
  (when-let [path (:selection @*pref-state)]
    (->> path io/file .getParentFile .getCanonicalPath
         (swap! *pref-state assoc :selection))))

(defn -onUp [this ^ActionEvent event]
  (-> event .getSource .getScene up!))

; save

(defn save! [^Scene scene]
  (when-let [path (:selection @*pref-state)]
    (when-let [pane (get-in @*runtime-state [:editor-panes path])]
      (when-let [engine (some-> pane (.lookup "#webview") .getEngine)]
        (e/save-file! path engine)))))

(defn -onSave [this ^ActionEvent event]
  (-> event .getSource .getScene save!))

; undo

(defn undo! [^Scene scene]
  (when-let [path (:selection @*pref-state)]
    (when-let [pane (get-in @*runtime-state [:editor-panes path])]
      (let [webview (.lookup pane "#webview")
            engine (.getEngine webview)]
        (.executeScript engine "undo()")
        (e/update-editor-buttons! pane engine)))))

(defn -onUndo [this ^ActionEvent event]
  (-> event .getSource .getScene undo!))

; redo

(defn redo! [^Scene scene]
  (when-let [path (:selection @*pref-state)]
    (when-let [pane (get-in @*runtime-state [:editor-panes path])]
      (let [webview (.lookup pane "#webview")
            engine (.getEngine webview)]
        (.executeScript engine "redo()")
        (e/update-editor-buttons! pane engine)))))

(defn -onRedo [this ^ActionEvent event]
  (-> event .getSource .getScene redo!))

; instaREPL

(defn toggle-instarepl! [^Scene scene & [from-button?]]
  (when-let [path (:selection @*pref-state)]
    (when-let [pane (get-in @*runtime-state [:editor-panes path])]
      (let [webview (.lookup pane "#webview")
            instarepl (.lookup pane "#instarepl")]
        (when-not from-button?
          (.setSelected instarepl (not (.isSelected instarepl))))
        (e/toggle-instarepl! (.getEngine webview) (.isSelected instarepl))))))

(defn -onInstaRepl [this ^ActionEvent event]
  (-> event .getSource .getScene (toggle-instarepl! true)))

; find

(defn focus-on-find! [^Scene scene]
  (when-let [path (:selection @*pref-state)]
    (when-let [pane (get-in @*runtime-state [:editor-panes path])]
      (when-let [find (.lookup pane "#find")]
        (doto find .requestFocus .selectAll)))))

(defn find! [^Scene scene ^KeyEvent event]
  (when (= KeyCode/ENTER (.getCode event))
    (when-let [path (:selection @*pref-state)]
      (when-let [pane (get-in @*runtime-state [:editor-panes path])]
        (let [webview (.lookup pane "#webview")
              engine (.getEngine webview)
              find (.lookup pane "#find")
              find-text (.getText find)]
          (-> engine
              (.executeScript "window")
              (.call "find" (into-array Object [find-text true (.isShiftDown event)]))))))))

(defn -onFind [this ^KeyEvent event]
  (-> event .getSource .getScene (find! event)))

; close

(defn close! [^Scene scene]
  (when-let [path (:selection @*pref-state)]
    (when (should-remove? scene path)
      (let [file (io/file path)
            new-path (if (.isDirectory file)
                       path
                       (.getCanonicalPath (.getParentFile file)))]
        (e/remove-editors! path *runtime-state)
        (up! scene)))))

(defn -onClose [this ^ActionEvent event]
  (-> event .getSource .getScene close!))

; theme

(defn dark-theme! [^Scene scene]
  (swap! *pref-state assoc :theme :dark)
  (-> scene .getStylesheets (.add "dark.css"))
  (u/update-webviews! @*pref-state @*runtime-state))

(defn -onDarkTheme [this ^ActionEvent event]
  (-> @*runtime-state :stage .getScene dark-theme!))

(defn light-theme! [^Scene scene]
  (swap! *pref-state assoc :theme :light)
  (-> scene .getStylesheets .clear)
  (u/update-webviews! @*pref-state @*runtime-state))

(defn -onLightTheme [this ^ActionEvent event]
  (-> @*runtime-state :stage .getScene light-theme!))

; font

(defn font! [^Scene scene]
  (-> scene .getRoot (.setStyle (str "-fx-font-size: " (u/normalize-text-size (:text-size @*pref-state)))))
  (u/update-webviews! @*pref-state @*runtime-state))

(defn font-dec! [^Scene scene]
  (swap! *pref-state update :text-size #(-> % (- 2) u/normalize-text-size))
  (font! scene))

(defn -onFontDec [this ^ActionEvent event]
  (-> @*runtime-state :stage .getScene font-dec!))

(defn font-inc! [^Scene scene]
  (swap! *pref-state update :text-size #(-> % (+ 2) u/normalize-text-size))
  (font! scene))

(defn -onFontInc [this ^ActionEvent event]
  (-> @*runtime-state :stage .getScene font-inc!))

; auto save

(defn -onAutoSave [this ^ActionEvent event]
  (swap! *pref-state assoc :auto-save? (-> event .getTarget .isSelected)))

; new file

(defn new-file! [^Scene scene]
  (let [dialog (doto (TextInputDialog.)
                 (.setTitle "New File")
                 (.setHeaderText "Enter a path relative to the selected directory.")
                 (.setGraphic nil)
                 (.initOwner (.getWindow scene))
                 (.initModality Modality/WINDOW_MODAL))
        selected-path (:selection @*pref-state)]
    (-> dialog .getEditor (.setText "example.clj"))
    (when-let [new-relative-path (-> dialog .showAndWait (.orElse nil))]
      (let [new-file (io/file selected-path new-relative-path)
            new-path (.getCanonicalPath new-file)
            project-tree (.lookup scene "#project_tree")]
        (.mkdirs (.getParentFile new-file))
        (.createNewFile new-file)
        (swap! *pref-state assoc :selection new-path)))))

(defn -onNewFile [this ^ActionEvent event]
  (-> event .getSource .getScene new-file!))

; open in file browser

(defn -onOpenInFileBrowser [this ^ActionEvent event]
  (-> event .getSource .getScene ui/open-in-file-browser!))

; open in browser

(defn -onOpenInWebBrowser [this ^ActionEvent event]
  (-> event .getSource .getScene ui/open-in-web-browser!))

; open docs in browser

(defn -onOpenDocsInWebBrowser [this ^ActionEvent event]
  (-> event .getSource .getScene (ui/open-in-web-browser!
                                   (str "http://localhost:" (:doc-port @*runtime-state)))))

; restart

(defn restart! [^Scene scene]
  (when-let [project (lu/get-project-dir)]
    (when-let [pane (get-in @*runtime-state [:projects (.getCanonicalPath project) :pane])]
      (a/start-app! pane (.getCanonicalPath project)))))

(defn -onRestart [this ^ActionEvent event]
  (-> event .getSource .getScene restart!))

; new version

(defn -onNewVersion [this ^ActionEvent event]
  (-> event .getSource .getScene (ui/open-in-web-browser! "https://sekao.net/lightmod")))

; export

(defmacro read-deps-edn []
  (let [deps (-> "deps.edn" slurp clojure.edn/read-string)]
    (with-out-str
      (clojure.pprint/pprint
        {:paths ["src" "resources"]
         :aliases (select-keys (:aliases deps) [:dev :prod :app])}))))

(defn export! [^Scene scene]
  (when-let [project (lu/get-project-dir)]
    (when (u/show-warning! scene "Export Project"
            "This will generate a Clojure CLI project. See clojure.org/guides/getting_started for details.")
      (let [project-name (str/replace (.getName project) "_" "-")
            chooser (doto (FileChooser.)
                      (.setTitle "Export Project")
                      (.setInitialFileName project-name))]
        (when-let [dir (.showSaveDialog chooser (.getWindow scene))]
          (apply-template! dir :export project-name (.getName project))
          (let [res (doto (io/file dir "resources" (.getName project))
                      .mkdirs)
                src (doto (io/file dir "src" (.getName project))
                      .mkdirs)
                copy-file! (fn [f]
                             (let [rel-path (u/get-relative-path (.getCanonicalPath project) (.getCanonicalPath f))
                                   dest (io/file
                                          (if (-> f .getName u/get-extension #{"clj" "cljs" "cljc"})
                                            src res)
                                          rel-path)]
                               (.mkdirs (.getParentFile dest))
                               (io/copy f dest)))]
            (spit (io/file dir "deps.edn") (read-deps-edn))
            (spit (io/file res "dev.cljs")
                  (with-out-str
                    (clojure.pprint/pprint
                      (list 'ns (symbol (str project-name ".dev"))
                        (list :require
                              [(symbol (str project-name ".client"))]
                              ['nightlight.repl-server])))))
            (doseq [f (.listFiles project)
                    :when (and (-> f .getName (.startsWith ".") not)
                               (-> f .getName (not= "main.js")))]
              (cond
                (.isFile f)
                (copy-file! f)
                (.isDirectory f)
                (doseq [f2 (file-seq f)
                        :when (.isFile f2)]
                  (copy-file! f2))))))))))

(defn -onExport [this ^ActionEvent event]
  (-> event .getSource .getScene export!))

