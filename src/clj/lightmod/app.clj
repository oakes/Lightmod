(ns lightmod.app
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [nightcode.state :refer [*runtime-state]]
            [nightcode.editors :as e]
            [nightcode.shortcuts :as shortcuts]
            [nightcode.utils :as u]
            [cljs.build.api :refer [build]]
            [hawk.core :as hawk]
            [lightmod.reload :as lr]
            [cljs.analyzer :as ana]
            [lightmod.repl :as lrepl]
            [lightmod.ui :as ui]
            [lightmod.utils :as lu]
            [clojure.core.async :refer [chan sliding-buffer put! <!! go-loop]]
            [cljs.env :as env])
  (:import [javafx.application Platform]
           [javafx.event EventHandler]))

(defn send-message! [project-pane dir msg]
  (when (lu/current-project? dir)
    (lr/send-message! dir msg)
    (Platform/runLater
      (fn []
        (try
          (when-let [obj (some-> project-pane
                                 (.lookup "#app")
                                 .getEngine
                                 (.executeScript "lightmod.loading"))]
            (when (instance? netscape.javascript.JSObject obj)
              (.call obj "show_error" (into-array [(pr-str msg)]))))
          (catch Exception _))))
    (and (not (:exception msg))
         (empty? (:warnings msg)))))

(defn compile-clj!
  ([dir]
   (compile-clj! dir nil))
  ([dir file-path]
   (try
     (when-not (.exists (io/file dir "server.clj"))
       (throw (Exception. "You must have a server.clj file.")))
     (lu/check-namespaces! dir true)
     (if file-path
       (load-file file-path)
       (doseq [f (lu/get-files-in-dep-order dir)]
         (load-file (.getCanonicalPath f))))
     {:type :visual-clj}
     (catch Exception e
       (.printStackTrace e)
       {:type :visual-clj
        :exception {:message (.getMessage e)}}))))

(defn get-cljs-options [dir]
  {:optimizations :none
   :source-map true
   :output-to (.getCanonicalPath (io/file dir "main.js"))
   :output-dir (.getCanonicalPath (io/file dir ".out"))
   :main (lu/path->ns dir "client")
   :asset-path ".out"
   :preloads '[lightmod.init]
   :foreign-libs (mapv #(update % :file lu/copy-from-resources! dir)
                   [{:file "js/p5.tiledmap.js"
                     :provides ["p5.tiled-map"]
                     :requires ["cljsjs.p5"]}
                    {:file "cljsjs/p5/development/p5.inc.js"
                     :provides ["cljsjs.p5"]}
                    {:file "cljsjs/react/development/react.inc.js"
                     :provides ["react" "cljsjs.react"]
                     :global-exports '{react React}}
                    {:file "cljsjs/create-react-class/development/create-react-class.inc.js"
                     :provides ["cljsjs.create-react-class" "create-react-class"]
                     :requires ["react"]
                     :global-exports '{create-react-class createReactClass}}
                    {:file "cljsjs/react-dom/development/react-dom.inc.js"
                     :provides ["react-dom" "cljsjs.react.dom"]
                     :requires ["react"]
                     :global-exports '{react-dom ReactDOM}}])
   :externs (mapv #(lu/copy-from-resources! % dir)
              ["cljsjs/react/common/react.ext.js"
               "cljsjs/create-react-class/common/create-react-class.ext.js"
               "cljsjs/react-dom/common/react-dom.ext.js"])})

(defn compile-cljs! [dir]
  (let [cljs-dir (io/file dir ".out" (-> dir io/file .getName))
        *warnings (atom [])
        on-warning (fn [warning-type env extra]
                     (when-not (#{:infer-warning} warning-type)
                       (swap! *warnings conj
                         (merge {:message (ana/error-message warning-type extra)
                                 :ns (-> env :ns :name)
                                 :type warning-type
                                 :file (str ana/*cljs-file*)}
                           (select-keys env [:line :column])))))
        opts (assoc (get-cljs-options dir)
               :warning-handlers [on-warning])]
    (try
      (when (.exists cljs-dir)
        (u/delete-children-recursively! cljs-dir))
      (catch Exception _))
    (try
      (when-not (.exists (io/file dir "client.cljs"))
        (throw (Exception. "You must have a client.cljs file.")))
      (lu/check-namespaces! dir false)
      (env/with-compiler-env (:*env @*runtime-state)
        (build dir opts))
      {:type :visual
       :warnings @*warnings}
      (catch Exception e
        (.printStackTrace e)
        {:type :visual
         :warnings @*warnings
         :exception (merge
                      {:message (.getMessage e)}
                      (select-keys (ex-data e) [:line :column]))}))))

(defn run-main! [project-pane dir]
  (try
    (let [-main (resolve (symbol (lu/path->ns dir "server") "-main"))]
      (when (nil? -main)
        (throw (Exception. "Can't find a -main function in your server.clj file.")))
      (let [server (-main)]
        (when-not (and (fn? server)
                       (-> server meta :local-port number?))
          (throw (Exception. "The -main function in server.clj must call run-server as its last step.")))
        server))
    (catch Exception e
      (.printStackTrace e)
      (send-message! project-pane dir
        {:type :visual-clj
         :exception {:message (.getMessage e)}})
      nil)))

(defn append! [webview s]
  (try
    (when (seq s)
      (-> webview
          .getEngine
          (.executeScript "window")
          (.call "append" (into-array [s]))))
    (catch Exception _)))

(defn redirect-stdio! [*server-logs]
  (let [stdout-pipes (lrepl/create-pipes)
        stderr-pipes (lrepl/create-pipes)]
    (intern 'clojure.core '*out* (:out stdout-pipes))
    (System/setErr (-> (:out stderr-pipes)
                       org.apache.commons.io.output.WriterOutputStream.
                       java.io.PrintStream.))
    (lrepl/pipe-into-console! (:in-pipe stdout-pipes)
      (fn [s]
        (binding [*out* (java.io.OutputStreamWriter. System/out)]
          (println s))
        (swap! *server-logs str s)))
    (lrepl/pipe-into-console! (:in-pipe stderr-pipes)
      (fn [s]
        (binding [*out* (java.io.OutputStreamWriter. System/out)]
          (println s))
        (swap! *server-logs str s \newline)))
    {:stdout stdout-pipes
     :stderr stderr-pipes}))

(defn init-server-logs! [inner-pane dir]
  (swap! *runtime-state update-in [:projects dir]
    (fn [project]
      (let [*server-logs (or (:*server-logs project)
                             (atom ""))
            pipes (redirect-stdio! *server-logs)
            webview (.lookup inner-pane "#server_logs_webview")
            bridge (ui/init-console! webview false
                     (fn []
                       (append! webview @*server-logs)
                       (add-watch *server-logs :append
                         (fn [_ _ old-log new-log]
                           (Platform/runLater
                             #(append! webview (subs new-log (count old-log)))))))
                     (fn []))]
        (assoc project
          :*server-logs *server-logs
          :server-logs-pipes pipes
          :server-logs-bridge bridge)))))

(defn init-reload-server! [dir]
  (let [reload-stop-fn (lr/start-reload-server! dir)
        reload-port (-> reload-stop-fn meta :local-port)
        f (io/file dir ".out" "lightmod.edn")]
    (doto f
      (-> .getParentFile .mkdirs)
      (spit (pr-str {:reload-port reload-port})))
    (swap! *runtime-state update-in [:projects dir] assoc
      :reload-stop-fn reload-stop-fn
      :clients #{})))

(defn stop-server! [dir]
  (let [{:keys [server reload-stop-fn reload-file-watcher
                *server-logs server-logs-pipes]}
        (get-in @*runtime-state [:projects dir])]
    (when server
      (try (server)
        (catch Exception _)))
    (when reload-stop-fn
      (try (reload-stop-fn)
        (catch Exception _)))
    (when reload-file-watcher (hawk/stop! reload-file-watcher))
    (when *server-logs
      (remove-watch *server-logs :append)
      (let [log-size (count @*server-logs)
            log-limit 10000]
        (when (> log-size log-limit)
          (swap! *server-logs subs (- log-size log-limit)))))
    (when-let [{:keys [stdout stderr]} server-logs-pipes]
      (doto stdout
        (-> :in-pipe .close)
        (-> :out .close))
      (doto stderr
        (-> :in-pipe .close)
        (-> :out .close)))))

(definterface AppBridge
  (onload [])
  (onevalcomplete [path results ns-name]))

(defn create-app-bridge [project-pane dir]
  (let [inner-pane (-> project-pane (.lookup "#project") .getItems (.get 1))
        client-repl-started? (atom false)]
    (reify AppBridge
      (onload [this]
        (swap! *runtime-state update-in [:projects dir]
          ui/init-client-repl! inner-pane dir)
        (swap! *runtime-state update-in [:projects dir]
          ui/init-server-repl! inner-pane dir))
      (onevalcomplete [this path results ns-name]
        (if-not path
          (let [result (-> results edn/read-string first)
                result (cond
                         (vector? result)
                         (str "Error: " (first result))
                         @client-repl-started?
                         result
                         :else
                         (do
                           (reset! client-repl-started? true)
                           nil))
                result (when (seq result)
                         (str result "\n"))
                result (str result ns-name "=> ")]
            (-> inner-pane
                (.lookup "#client_repl_webview")
                .getEngine
                (.executeScript "window")
                (.call "append" (into-array [result]))))
          (when-let [editor (get-in @*runtime-state [:editor-panes path])]
            (-> editor
                (.lookup "#webview")
                .getEngine
                (.executeScript "window")
                (.call "setInstaRepl" (into-array [results])))))))))

(defn start-server! [project-pane dir]
  ; compile the clj
  (when (->> (compile-clj! dir)
             (send-message! project-pane dir))
    ; compile the cljs
    (when (->> (compile-cljs! dir)
               (send-message! project-pane dir))
      ; run the server's main function and load the webview
      (when-let [server (run-main! project-pane dir)]
        (let [port (-> server meta :local-port)
              url (str "http://localhost:" port "/"
                    (-> dir io/file .getName)
                    "/index.html")
              bridge (create-app-bridge project-pane dir)]
          (Platform/runLater
            (fn []
              (let [app (.lookup project-pane "#app")]
                (.setContextMenuEnabled app false)
                (.setOnStatusChanged (.getEngine app)
                  (reify EventHandler
                    (handle [this event]
                      (-> (.getEngine app)
                          (.executeScript "window")
                          (.setMember "java" bridge)))))
                (-> app .getEngine (.load url)))))
          (swap! *runtime-state update-in [:projects dir] merge
            {:url url
             :server server
             :app-bridge bridge
             :editor-file-watcher
             (or (get-in @*runtime-state [:projects dir :editor-file-watcher])
                 (e/create-file-watcher dir *runtime-state))
             :reload-file-watcher
             (hawk/watch! [{:paths [dir]
                            :handler (fn [ctx {:keys [kind file]}]
                                       (let [in-out-dir? (-> (io/file dir ".out")
                                                             .getCanonicalPath
                                                             (u/parent-path? (.getCanonicalPath file)))]
                                         (when (and (not in-out-dir?)
                                                    (= kind :modify)
                                                    (some #(-> file .getName (.endsWith %)) [".clj" ".cljc"]))
                                           (->> (compile-clj! dir (.getCanonicalPath file))
                                                (send-message! project-pane dir)))
                                         (cond
                                           (and (not in-out-dir?)
                                                (some #(-> file .getName (.endsWith %)) [".cljs" ".cljc"]))
                                           (->> (compile-cljs! dir)
                                                (send-message! project-pane dir))
                                           (or in-out-dir?
                                               (some #(-> file .getName (.endsWith %)) [".css" ".html"]))
                                           (lr/reload-file! dir file))
                                         ctx))}])}))))))

(defn start-build-thread! []
  (let [c (chan (sliding-buffer 1))]
    (go-loop []
      (let [dir (<!! c)]
        (when-let [project-pane (get-in @*runtime-state [:projects dir :pane])]
          (start-server! project-pane dir)))
      (recur))
    c))

(defn stop-app! [project-pane dir]
  (stop-server! dir)
  (doto (.lookup project-pane "#app")
    (-> .getEngine (.loadContent "<html><body></body></html>"))))

(defn start-app! [project-pane dir]
  (stop-app! project-pane dir)
  (swap! *runtime-state assoc-in [:projects dir :pane] project-pane)
  (init-reload-server! dir)
  (-> project-pane (.lookup "#project") .getItems (.get 1) (init-server-logs! dir))
  (let [app (.lookup project-pane "#app")]
    (.setOnStatusChanged (.getEngine app)
      (reify EventHandler
        (handle [this event]
          (-> (.getEngine app)
              (.executeScript "window")
              (.setMember "java"
                (reify AppBridge
                  (onload [this]
                    (-> @*runtime-state :build-chan (put! dir)))
                  (onevalcomplete [this path results ns-name])))))))
    (-> app .getEngine (.load (str "http://localhost:"
                                (:web-port @*runtime-state)
                                "/loading.html")))))

