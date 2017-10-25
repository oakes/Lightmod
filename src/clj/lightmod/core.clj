(ns lightmod.core
  (:require [clojure.java.io :as io]
            [lightmod.controller :as c]
            [lightmod.game :as g]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.state :as s :refer [pref-state runtime-state]])
  (:import [javafx.application Application]
           [javafx.fxml FXMLLoader]
           [javafx.stage Stage]
           [javafx.scene Scene]
           [java.util.prefs Preferences])
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
        scene (Scene. root 1242 768)]
    (swap! runtime-state assoc :stage stage)
    (intern 'nightcode.state 'prefs (.node (Preferences/userRoot) "lightmod"))
    (swap! pref-state assoc :theme (s/read-pref :theme :light))
    (doto stage
      (.setTitle "Lightmod 1.0.0")
      (.setScene scene)
      (.show))
    (let [editors (.lookup scene "#editors")
          dir (io/file (System/getProperty "user.home") "Lightmod" "hello-world")
          file (io/file dir "core.cljs")
          path (.getCanonicalPath file)]
      (when-let [pane (or (get-in @runtime-state [:editor-panes path])
                          (e/editor-pane pref-state runtime-state file))]
        (-> editors .getChildren (.add pane))
        (swap! runtime-state update :editor-panes assoc path pane)
        (swap! runtime-state assoc :project-dir (.getCanonicalPath dir))
        (swap! pref-state assoc :selection path)))
    (g/init-game! scene)
    (shortcuts/set-shortcut-listeners! stage pref-state runtime-state actions)
    ; apply the prefs
    #_
    (let [theme-buttons (->> (.lookup scene "#start")
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
    #_
    (let [auto-save-button (->> (.lookup scene "#start")
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

