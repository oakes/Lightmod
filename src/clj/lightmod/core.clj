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
    (swap! runtime-state assoc :stage stage :prefs (.node (Preferences/userRoot) "lightmod"))
    (init-pref-state! {:selection nil
                       :theme :light
                       :text-size 16
                       :auto-save? true})
    (doto stage
      (.setTitle "Lightmod 1.0.0")
      (.setScene scene)
      (.show))
    (a/set-selection-listener! scene)
    (let [dir (io/file (System/getProperty "user.home") "LightmodProjects" "hello-world")
          file (io/file dir "client.cljs")
          path (.getCanonicalPath file)]
      (swap! runtime-state assoc :current-project (.getCanonicalPath dir))
      (swap! pref-state assoc :selection (.getCanonicalPath dir))
      (a/init-app! scene (.getCanonicalPath dir)))
    (shortcuts/set-shortcut-listeners! stage pref-state runtime-state actions)
    (-> scene (.lookup "#app") (.setContextMenuEnabled false))
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

