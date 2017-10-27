(ns lightmod.core
  (:require [clojure.java.io :as io]
            [lightmod.controller :as c]
            [lightmod.app :as a]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.state :refer [pref-state runtime-state init-pref-state!]])
  (:import [javafx.application Application]
           [javafx.fxml FXMLLoader]
           [javafx.stage Stage]
           [javafx.scene Scene]
           [javafx.scene.control Tab]
           [java.util.prefs Preferences]
           [javafx.event EventHandler])
  (:gen-class :extends javafx.application.Application))

(def actions {:#up c/up!
              :#save c/save!
              :#undo c/undo!
              :#redo c/redo!
              :#instarepl c/toggle-instarepl!
              :#find c/focus-on-find!
              :#close c/close!
              :#new_file c/new-file!
              :#open_in_file_browser c/open-in-file-browser!})

(defn -start [^lightmod.core app ^Stage stage]
  (let [root (FXMLLoader/load (io/resource "main.fxml"))
        scene (Scene. root 1242 768)
        projects (.lookup scene "#projects")
        projects-dir (io/file (System/getProperty "user.home") "LightmodProjects")]
    (System/setProperty "user.dir" (.getCanonicalPath projects-dir))
    ; create project tabs
    (doseq [file (.listFiles projects-dir)
            :let [dir (.getCanonicalPath file)]
            :when (and (.isDirectory file)
                       (-> file .getName (.startsWith ".") not))]
      (let [project-pane (FXMLLoader/load (io/resource "project.fxml"))]
        (-> projects
            .getTabs
            (.add (doto (Tab.)
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
                                (swap! pref-state assoc :selection
                                  (.getCanonicalPath file))
                                (when-not (get-in @runtime-state [:projects dir])
                                  (a/init-app! project-pane dir)))
                              (let [content (-> event .getTarget .getContent)
                                    editors (-> content
                                                (.lookup "#project")
                                                .getItems
                                                (.get 1)
                                                (.lookup "#editors"))]
                                (shortcuts/hide-tooltips! content)
                                (.clear (.getChildren editors)))))))))))))
    ; initialize state
    (swap! runtime-state assoc
      :stage stage
      :prefs (.node (Preferences/userRoot) "lightmod")
      :projects-dir projects-dir)
    (a/set-selection-listener! scene)
    (init-pref-state! {:selection nil
                       :theme :light
                       :text-size 16
                       :auto-save? true})
    (doto stage
      (.setTitle "Lightmod 1.0.0")
      (.setScene scene)
      (.show))
    (shortcuts/set-shortcut-listeners! stage pref-state runtime-state actions)
    ; apply the prefs
    (let [theme-buttons (->> (.lookup scene "#settings")
                             .getItems
                             (filter #(= "theme_buttons" (.getId %)))
                             first
                             .getContent
                             .getChildren)]
      (case (:theme @pref-state)
        :dark (.fire (.get theme-buttons 0))
        :light (.fire (.get theme-buttons 1))
        nil))
    (c/font! scene)
    (let [auto-save-button (->> (.lookup scene "#settings")
                                .getItems
                                (filter #(= "auto_save" (.getId %)))
                                first)]
      (.setSelected auto-save-button (:auto-save? @pref-state)))))
                          

(defn -main [& args]
  (when (= "Linux" (System/getProperty "os.name"))
    (System/setProperty "prism.lcdtext" "false")
    (System/setProperty "prism.text" "t2k"))
  (swap! runtime-state assoc :web-port (e/start-web-server!))
  (Application/launch lightmod.core (into-array String args)))

(defn dev-main [] (-main))

