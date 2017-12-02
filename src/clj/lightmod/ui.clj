(ns lightmod.ui
  (:require [clojure.java.io :as io]
            [lightmod.repl :as lrepl]
            [lightmod.utils :as lu]
            [nightcode.utils :as u]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.state :refer [pref-state runtime-state]])
  (:import [javafx.application Platform]
           [javafx.scene.control Alert Alert$AlertType Tab]
           [javafx.event EventHandler]
           [java.awt Desktop]
           [javafx.fxml FXMLLoader]
           [javafx.scene Scene]
           [javafx.scene.image ImageView]
           [javafx.scene.control Button ContentDisplay Label]
           [javafx.beans.value ChangeListener]
           [nightcode.utils Bridge]))

(defn open-in-file-browser!
  ([^Scene scene]
   (open-in-file-browser! scene (:selection @pref-state)))
  ([^Scene scene path]
   (javax.swing.SwingUtilities/invokeLater
     (fn []
       (when (Desktop/isDesktopSupported)
         (.open (Desktop/getDesktop) (io/file path)))))))

(defn open-in-web-browser!
  ([^Scene scene]
   (when-let [project (lu/get-project-dir)]
     (when-let [url (get-in @runtime-state [:projects (.getCanonicalPath project) :url])]
       (open-in-web-browser! scene url))))
  ([^Scene scene url]
   (javax.swing.SwingUtilities/invokeLater
     (fn []
       (when (Desktop/isDesktopSupported)
         (.browse (Desktop/getDesktop) (java.net.URI. url)))))))

(defn alert! [message]
  (doto (Alert. Alert$AlertType/INFORMATION)
    (.setContentText message)
    (.setHeaderText nil)
    .showAndWait))

(defn create-tab [^Scene scene file start-app! stop-app!]
  (let [project-pane (FXMLLoader/load (io/resource "project.fxml"))
        dir (.getCanonicalPath file)]
    (doto (Tab.)
      (.setText (.getName file))
      (.setContent project-pane)
      (.setClosable true)
      (.setOnCloseRequest
        (reify EventHandler
          (handle [this event]
            (.consume event))))
      (.setOnSelectionChanged
        (reify EventHandler
          (handle [this event]
            (when (-> event .getTarget .isClosable)
              (if (-> event .getTarget .isSelected)
                (do
                  (swap! pref-state assoc :selection dir)
                  (start-app! project-pane dir))
                (let [editors (-> project-pane
                                  (.lookup "#project")
                                  .getItems
                                  (.get 1)
                                  (.lookup "#editors"))]
                  (stop-app! project-pane dir)
                  (shortcuts/hide-tooltips! project-pane)
                  (.clear (.getChildren editors))))))))
      (.setOnCloseRequest
        (reify EventHandler
          (handle [this event]
            (alert! "To delete this project, you'll need to delete its folder.")
            (open-in-file-browser! scene dir)
            (.consume event)))))))

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

(defn eval-cljs-code [path dir code]
  (when-let [pane (get-in @runtime-state [:projects dir :pane])]
    (when-let [app (.lookup pane "#app")]
      (try
        (some-> (.getEngine app)
                (.executeScript "lightmod.init")
                (.call "eval_code" (into-array [path code])))
        (catch Exception _))
      nil)))

(defn set-selection-listener! [scene]
  (add-watch pref-state :selection-changed
    (fn [_ _ _ {:keys [selection]}]
      (when selection
        (let [file (io/file selection)]
          (when-let [project-dir (lu/get-project-dir file)]
            (when-let [tab (->> (.lookup scene "#projects")
                                .getTabs
                                (filter #(= (.getText %) (.getName project-dir)))
                                first)]
              (when-let [pane (or (when (.isDirectory file)
                                    (dir-pane file))
                                  (get-in @runtime-state [:editor-panes selection])
                                  (when-let [new-editor (e/editor-pane pref-state runtime-state file
                                                          (case (-> file .getName u/get-extension)
                                                            ("clj" "cljc") e/eval-code
                                                            "cljs" (partial eval-cljs-code selection (.getCanonicalPath project-dir))
                                                            nil))]
                                    (swap! runtime-state update :editor-panes assoc selection new-editor)
                                    new-editor))]
                (let [content (.getContent tab)
                      editors (-> content
                                  (.lookup "#project")
                                  .getItems
                                  (.get 1)
                                  (.lookup "#editors"))]
                  (shortcuts/hide-tooltips! content)
                  (doto (.getChildren editors)
                    (.clear)
                    (.add pane))
                  (.setDisable (.lookup editors "#up") (= selection (.getCanonicalPath project-dir)))
                  (.setDisable (.lookup editors "#close") (.isDirectory file))
                  (Platform/runLater
                    (fn []
                      (some-> (.lookup pane "#webview") .requestFocus))))))))))))

(defn init-console! [webview repl? on-load on-enter]
  (doto webview
    (.setVisible true)
    (.setContextMenuEnabled false))
  (let [engine (.getEngine webview)
        bridge (reify Bridge
                 (onload [this]
                   (try
                     (doto (.getEngine webview)
                       (.executeScript (if repl? "initConsole(true)" "initConsole(false)"))
                       (.executeScript (case (:theme @pref-state)
                                         :dark "changeTheme(true)"
                                         :light "changeTheme(false)"))
                       (.executeScript (format "setTextSize(%s)" (:text-size @pref-state))))
                     (on-load)
                     (catch Exception e (.printStackTrace e))))
                 (onautosave [this])
                 (onchange [this])
                 (onenter [this text]
                   (on-enter text))
                 (oneval [this code]))]
    (.setOnStatusChanged engine
      (reify EventHandler
        (handle [this event]
          (-> engine
              (.executeScript "window")
              (.setMember "java" bridge)))))
    (.load engine (str "http://localhost:"
                    (:web-port @runtime-state)
                    "/paren-soup.html"))
    bridge))

(defn init-client-repl! [project inner-pane dir]
  (let [webview (.lookup inner-pane "#client_repl_webview")
        start-ns (symbol (lu/path->ns dir "client"))
        on-recv (fn [text]
                  (Platform/runLater
                    (fn []
                      (eval-cljs-code nil dir (pr-str [text])))))]
    (assoc project
      :client-repl-bridge
      (init-console! webview true
        #(on-recv (pr-str (list 'ns start-ns)))
        on-recv))))

(defn init-server-repl! [{:keys [server-repl-pipes] :as project} inner-pane dir]
  (when-let [{:keys [in-pipe out-pipe]} server-repl-pipes]
    (doto out-pipe (.write "lightmod.repl/exit\n") (.flush))
    (.close in-pipe))
  (let [webview (.lookup inner-pane "#server_repl_webview")
        pipes (lrepl/create-pipes)
        start-ns (symbol (lu/path->ns dir "server"))
        on-recv (fn [text]
                  (Platform/runLater
                    (fn []
                      (-> (.getEngine webview)
                          (.executeScript "window")
                          (.call "append" (into-array [text]))))))]
    (assoc project
      :server-repl-bridge
      (init-console! webview true
        #(on-recv (str start-ns "=> "))
        (fn [text]
          (doto (:out-pipe pipes)
            (.write text)
            (.flush))))
      :server-repl-pipes
      (lrepl/start-repl-thread! pipes start-ns on-recv))))

(defn init-docs! [^Scene scene]
  (let [docs (.lookup scene "#docs")
        back-btn (.lookup scene "#back")
        forward-btn (.lookup scene "#forward")
        ^Tab tab (-> (.lookup scene "#projects") .getTabs second)
        engine (.getEngine docs)
        history (-> docs .getEngine .getHistory)]
    (.setOnAction back-btn
      (reify EventHandler
        (handle [this event]
          (.go history -1))))
    (.setOnAction forward-btn
      (reify EventHandler
        (handle [this event]
          (.go history 1))))
    (-> history
        .currentIndexProperty
        (.addListener
          (reify ChangeListener
            (changed [this observable old-value new-value]
              (.setDisable back-btn (= 0 (.getCurrentIndex history)))
              (.setDisable forward-btn (-> history .getEntries count dec (= (.getCurrentIndex history))))))))
    (.setOnSelectionChanged tab
      (reify EventHandler
        (handle [this event]
          (when (-> event .getTarget .isSelected)
            (.reload engine)))))
    (.load engine (str "http://localhost:" (:doc-port @runtime-state)))))

