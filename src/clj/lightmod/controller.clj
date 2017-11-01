(ns lightmod.controller
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nightcode.editors :as e]
            [nightcode.state :refer [pref-state runtime-state]]
            [nightcode.utils :as u]
            [lightmod.app :as a])
  (:import [javafx.event ActionEvent]
           [javafx.scene.control Alert Alert$AlertType ButtonType TextInputDialog]
           [javafx.stage DirectoryChooser FileChooser StageStyle Window Modality]
           [javafx.application Platform]
           [javafx.scene Scene]
           [javafx.scene.input KeyEvent KeyCode]
           [java.awt Desktop])
  (:gen-class
   :methods [[onRename [javafx.event.ActionEvent] void]
             [onRemove [javafx.event.ActionEvent] void]
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
             [onRestart [javafx.event.ActionEvent] void]]))

; remove

(defn should-remove? [^Scene scene ^String path]
  (let [paths-to-delete (->> @runtime-state :editor-panes keys (filter #(u/parent-path? path %)))
        get-pane #(get-in @runtime-state [:editor-panes %])
        get-engine #(-> % get-pane (.lookup "#webview") .getEngine)
        unsaved? #(-> % get-engine (.executeScript "isClean()") not)
        unsaved-paths (filter unsaved? paths-to-delete)]
    (or (empty? unsaved-paths)
        (->> (map #(-> % io/file .getName) unsaved-paths)
             (str/join \newline)
             (str "The below files are not saved. Proceed?" \newline \newline)
             (u/show-warning! scene "Unsaved Files")))))

(defn remove! [^Scene scene]
  (let [{:keys [project-set selection]} @pref-state
        message (if (contains? project-set selection)
                  "Remove this project? It WILL NOT be deleted from the disk."
                  "Remove this file? It WILL be deleted from the disk.")
        dialog (doto (Alert. Alert$AlertType/CONFIRMATION)
                 (.setTitle "Remove")
                 (.setHeaderText message)
                 (.setGraphic nil)
                 (.initOwner (.getWindow scene))
                 (.initModality Modality/WINDOW_MODAL))
        project-tree (.lookup scene "#project_tree")]
    (when (and (-> dialog .showAndWait (.orElse nil) (= ButtonType/OK))
               (should-remove? scene selection))
      (e/remove-editors! selection runtime-state))))

(defn -onRemove [this ^ActionEvent event]
  (-> event .getSource .getScene remove!))

; up

(defn up! [^Scene scene]
  (when-let [path (:selection @pref-state)]
    (->> path io/file .getParentFile .getCanonicalPath
         (swap! pref-state assoc :selection))))

(defn -onUp [this ^ActionEvent event]
  (-> event .getSource .getScene up!))

; save

(defn save! [^Scene scene]
  (when-let [path (:selection @pref-state)]
    (when-let [engine (some-> scene (.lookup "#webview") .getEngine)]
      (e/save-file! path engine))))

(defn -onSave [this ^ActionEvent event]
  (-> event .getSource .getScene save!))

; undo

(defn undo! [^Scene scene]
  (let [editor (.lookup scene "#editor")
        webview (.lookup scene "#webview")
        engine (.getEngine webview)]
    (.executeScript engine "undo()")
    (e/update-editor-buttons! editor engine)))

(defn -onUndo [this ^ActionEvent event]
  (-> event .getSource .getScene undo!))

; redo

(defn redo! [^Scene scene]
  (let [editor (.lookup scene "#editor")
        webview (.lookup scene "#webview")
        engine (.getEngine webview)]
    (.executeScript engine "redo()")
    (e/update-editor-buttons! editor engine)))

(defn -onRedo [this ^ActionEvent event]
  (-> event .getSource .getScene redo!))

; instaREPL

(defn toggle-instarepl! [^Scene scene & [from-button?]]
  (let [webview (.lookup scene "#webview")
        instarepl (.lookup scene "#instarepl")]
    (when-not from-button?
      (.setSelected instarepl (not (.isSelected instarepl))))
    (e/toggle-instarepl! (.getEngine webview) (.isSelected instarepl))))

(defn -onInstaRepl [this ^ActionEvent event]
  (-> event .getSource .getScene (toggle-instarepl! true)))

; find

(defn focus-on-find! [^Scene scene]
  (when-let [find (.lookup scene "#find")]
    (doto find .requestFocus .selectAll)))

(defn find! [^Scene scene ^KeyEvent event]
  (when (= KeyCode/ENTER (.getCode event))
    (when-let [path (:selection @pref-state)]
      (let [webview (.lookup scene "#webview")
            engine (.getEngine webview)
            find (.lookup scene "#find")
            find-text (.getText find)]
        (-> engine
            (.executeScript "window")
            (.call "find" (into-array Object [find-text true (.isShiftDown event)])))))))

(defn -onFind [this ^KeyEvent event]
  (-> event .getSource .getScene (find! event)))

; close

(defn close! [^Scene scene]
  (when-let [path (:selection @pref-state)]
    (when (should-remove? scene path)
      (let [file (io/file path)
            new-path (if (.isDirectory file)
                       path
                       (.getCanonicalPath (.getParentFile file)))]
        (e/remove-editors! path runtime-state)
        (up! scene)))))

(defn -onClose [this ^ActionEvent event]
  (-> event .getSource .getScene close!))

; theme

(defn dark-theme! [^Scene scene]
  (swap! pref-state assoc :theme :dark)
  (-> scene .getStylesheets (.add "dark.css"))
  (u/update-webviews! @pref-state @runtime-state))

(defn -onDarkTheme [this ^ActionEvent event]
  (-> @runtime-state :stage .getScene dark-theme!))

(defn light-theme! [^Scene scene]
  (swap! pref-state assoc :theme :light)
  (-> scene .getStylesheets .clear)
  (u/update-webviews! @pref-state @runtime-state))

(defn -onLightTheme [this ^ActionEvent event]
  (-> @runtime-state :stage .getScene light-theme!))

; font

(defn font! [^Scene scene]
  (-> scene .getRoot (.setStyle (str "-fx-font-size: " (u/normalize-text-size (:text-size @pref-state)))))
  (u/update-webviews! @pref-state @runtime-state))

(defn font-dec! [^Scene scene]
  (swap! pref-state update :text-size #(-> % (- 2) u/normalize-text-size))
  (font! scene))

(defn -onFontDec [this ^ActionEvent event]
  (-> @runtime-state :stage .getScene font-dec!))

(defn font-inc! [^Scene scene]
  (swap! pref-state update :text-size #(-> % (+ 2) u/normalize-text-size))
  (font! scene))

(defn -onFontInc [this ^ActionEvent event]
  (-> @runtime-state :stage .getScene font-inc!))

; auto save

(defn -onAutoSave [this ^ActionEvent event]
  (swap! pref-state assoc :auto-save? (-> event .getTarget .isSelected)))

; new file

(defn new-file! [^Scene scene]
  (let [dialog (doto (TextInputDialog.)
                 (.setTitle "New File")
                 (.setHeaderText "Enter a path relative to the selected directory.")
                 (.setGraphic nil)
                 (.initOwner (.getWindow scene))
                 (.initModality Modality/WINDOW_MODAL))
        selected-path (:selection @pref-state)]
    (-> dialog .getEditor (.setText "example.clj"))
    (when-let [new-relative-path (-> dialog .showAndWait (.orElse nil))]
      (let [new-file (io/file selected-path new-relative-path)
            new-path (.getCanonicalPath new-file)
            project-tree (.lookup scene "#project_tree")]
        (.mkdirs (.getParentFile new-file))
        (.createNewFile new-file)
        (swap! pref-state assoc :selection new-path)))))

(defn -onNewFile [this ^ActionEvent event]
  (-> event .getSource .getScene new-file!))

; open in file browser

(defn open-in-file-browser! [^Scene scene]
  (when-let [path (:selection @pref-state)]
    (javax.swing.SwingUtilities/invokeLater
      (fn []
        (when (Desktop/isDesktopSupported)
          (.open (Desktop/getDesktop) (io/file path)))))))

(defn -onOpenInFileBrowser [this ^ActionEvent event]
  (-> event .getSource .getScene open-in-file-browser!))

; open in browser

(defn open-in-web-browser! [^Scene scene]
  (when-let [project (a/get-project-dir)]
    (when-let [url (get-in @runtime-state [:projects (.getCanonicalPath project) :url])]
      (javax.swing.SwingUtilities/invokeLater
        (fn []
          (when (Desktop/isDesktopSupported)
            (.browse (Desktop/getDesktop) (java.net.URI. url))))))))

(defn -onOpenInWebBrowser [this ^ActionEvent event]
  (-> event .getSource .getScene open-in-web-browser!))

; restart

(defn restart! [^Scene scene]
  (when-let [project (a/get-project-dir)]
    (when-let [pane (get-in @runtime-state [:projects (.getCanonicalPath project) :pane])]
      (a/start-app! pane (.getCanonicalPath project)))))

(defn -onRestart [this ^ActionEvent event]
  (-> event .getSource .getScene restart!))

