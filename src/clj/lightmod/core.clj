(ns lightmod.core
  (:require [clojure.java.io :as io]
            [lightmod.controller :as c]
            [lightmod.app :as a]
            [lightmod.ui :as ui]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.state :refer [*pref-state *runtime-state init-pref-state!]]
            [nightcode.utils :as u]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.util.response :refer [redirect not-found]]
            [ring.middleware.reload]
            [clojure.data.json :as json]
            [cljs.env :as env]
            [dynadoc.core :as dyn])
  (:import [javafx.application Application Platform]
           [javafx.fxml FXMLLoader]
           [javafx.stage Stage]
           [javafx.scene Scene]
           [java.util.prefs Preferences]
           [javafx.event EventHandler]
           [javafx.beans.value ChangeListener])
  (:gen-class :extends javafx.application.Application))

(def version "1.2.0")

(def actions {:#up c/up!
              :#save c/save!
              :#undo c/undo!
              :#redo c/redo!
              :#instarepl c/toggle-instarepl!
              :#find c/focus-on-find!
              :#close c/close!
              :#new_file c/new-file!
              :#open_in_file_browser ui/open-in-file-browser!})

(defn -start [^lightmod.core app ^Stage stage]
  (let [root (FXMLLoader/load (io/resource "main.fxml"))
        scene (Scene. root 1242 768)
        projects (.lookup scene "#projects")
        projects-dir (io/file (System/getProperty "user.home") "LightmodProjects")]
    (intern 'nightcode.shortcuts 'show-tooltip! (fn [& _])) ; don't show tooltips for now
    (System/setProperty "user.dir" (.getCanonicalPath projects-dir))
    ; create project tabs
    (doseq [file (.listFiles projects-dir)
            :when (and (.isDirectory file)
                       (-> file .getName (.startsWith ".") not))]
      ; delete the .out dir in case Lightmod updated any libraries
      (let [out-dir (io/file file ".out")]
        (try
          (when (.exists out-dir)
            (u/delete-children-recursively! out-dir))
          (catch Exception _))
        (.mkdir out-dir))
      ; create tab
      (-> projects .getTabs (.add (ui/create-tab scene file a/start-app! a/stop-app!))))
    ; initialize state
    (swap! *runtime-state assoc
      :stage stage
      :prefs (.node (Preferences/userRoot) "lightmod")
      :projects-dir projects-dir
      :build-chan (a/start-build-thread!))
    (ui/set-selection-listener! scene)
    (init-pref-state! {:selection nil
                       :theme :light
                       :text-size 16
                       :auto-save? true})
    ; show window
    (doto stage
      (.setTitle (str "Lightmod " version))
      (.setScene scene)
      (.show))
    ; initialize docs tab
    (ui/init-docs! scene)
    ; set up shortcuts
    (shortcuts/set-shortcut-listeners! stage *pref-state *runtime-state actions)
    ; apply the prefs
    (let [theme-buttons (->> (.lookup scene "#settings")
                             .getItems
                             (filter #(= "theme_buttons" (.getId %)))
                             first
                             .getContent
                             .getChildren)]
      (case (:theme @*pref-state)
        :dark (.fire (.get theme-buttons 0))
        :light (.fire (.get theme-buttons 1))
        nil))
    (c/font! scene)
    (let [auto-save-button (->> (.lookup scene "#settings")
                                .getItems
                                (filter #(= "auto_save" (.getId %)))
                                first)]
      (.setSelected auto-save-button (:auto-save? @*pref-state)))
    ; refresh things on window focus
    (.addListener (.focusedProperty stage)
      (reify ChangeListener
        (changed [this observable old-value new-value]
          (when new-value
            (let [tabs (.getTabs projects)
                  tab-names (set (mapv #(.getText %) tabs))]
              ; remove tabs if their directory disappeared
              (doseq [tab tabs
                      :let [f (->> tab .getText (io/file projects-dir))
                            dir (.getCanonicalPath f)]
                      :when (and (.isClosable tab)
                                 (-> f .exists not))]
                (.remove tabs tab))
              ; add tabs for directories that appeared
              (doseq [f (.listFiles projects-dir)
                      :when (and (.isDirectory f)
                                 (-> f .getName (.startsWith ".") not)
                                 (-> f .getName tab-names not))]
                (.add tabs (ui/create-tab scene f a/start-app! a/stop-app!))))
            ; remove any editors whose files no longer exist
            (e/remove-non-existing-editors! *runtime-state)
            ; force existing selection to refresh
            (when-let [selection (:selection @*pref-state)]
              (doto *pref-state
                (swap! assoc :selection nil)
                (swap! assoc :selection selection)))))))
    ; check for updates
    (when-not (:dev? @*runtime-state)
      (future
        (try
          (when (some-> "https://clojars.org/api/artifacts/lightmod"
                        slurp
                        json/read-str
                        (get "latest_release")
                        (not= version))
            (Platform/runLater
              (fn []
                (-> (.lookup scene "#new_version")
                    (.setVisible true)))))
          (catch Exception _))))))

(defn handler [request]
  (case (:uri request)
    "/" (redirect "/paren-soup.html")
    (not-found "")))

(defn start-web-server! []
  (-> handler
      (wrap-resource "public")
      (wrap-content-type)
      (run-jetty {:port 0 :join? false})
      .getConnectors
      (aget 0)
      .getLocalPort))

(defn -main [& args]
  (when (= "Linux" (System/getProperty "os.name"))
    (System/setProperty "prism.lcdtext" "false")
    (System/setProperty "prism.text" "t2k"))
  (let [*env (env/default-compiler-env)]
    (swap! *runtime-state assoc
      :web-port (start-web-server!)
      :doc-port (-> {:port 0 :*cljs-env *env :disable-update-checker? true}
                    dyn/start
                    meta
                    :local-port)
      :*env *env))
  (Application/launch lightmod.core (into-array String args)))

(defn dev-main []
  (swap! *runtime-state assoc :dev? true)
  (-main))

