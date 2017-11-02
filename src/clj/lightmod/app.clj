(ns lightmod.app
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [nightcode.state :refer [pref-state runtime-state]]
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
            [eval-soup.core :refer [with-security]]
            [eval-soup.clojail :refer [thunk-timeout]])
  (:import [javafx.application Platform]
           [javafx.event EventHandler]))

(defn send-message! [project-pane dir msg]
  (lr/send-message! dir msg)
  (Platform/runLater
    (fn []
      (when-let [obj (some-> project-pane
                             (.lookup "#app")
                             .getEngine
                             (.executeScript "lightmod.loading"))]
        (when (instance? netscape.javascript.JSObject obj)
          (.call obj "show_error" (into-array [(pr-str msg)])))))))

(defn compile-clj!
  ([project-pane dir]
   (compile-clj! project-pane dir nil))
  ([project-pane dir file-path]
   (try
     (when-not (.exists (io/file dir "server.clj"))
       (throw (Exception. "You must have a server.clj file.")))
     (lu/check-namespaces! dir true)
     (with-security
       (if file-path
         (load-file file-path)
         (doseq [f (lu/get-files-in-dep-order dir)]
           (load-file (.getCanonicalPath f)))))
     (send-message! project-pane dir {:type :visual-clj})
     true
     (catch Exception e
       (.printStackTrace e)
       (send-message! project-pane dir
         {:type :visual-clj
          :exception {:message (.getMessage e)}})
       false))))

(defn compile-cljs! [project-pane dir]
  (let [cljs-dir (io/file dir ".out" (-> dir io/file .getName))
        warnings (atom [])
        on-warning (fn [warning-type env extra]
                     (when-not (#{:infer-warning} warning-type)
                       (swap! warnings conj
                         (merge {:message (ana/error-message warning-type extra)
                                 :ns (-> env :ns :name)
                                 :type warning-type
                                 :file (str ana/*cljs-file*)}
                           (select-keys env [:line :column])))))]
    (try
      (when (.exists cljs-dir)
        (u/delete-children-recursively! cljs-dir))
      (catch Exception _))
    (try
      (when-not (.exists (io/file dir "client.cljs"))
        (throw (Exception. "You must have a client.cljs file.")))
      (lu/check-namespaces! dir false)
      (build dir
        {:output-to (.getCanonicalPath (io/file dir "main.js"))
         :output-dir (.getCanonicalPath (io/file dir ".out"))
         :main (lu/path->ns dir "client")
         :asset-path ".out"
         :preloads '[lightmod.init]
         :foreign-libs (mapv #(update % :file lu/copy-from-resources! dir)
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
         :externs (mapv #(lu/copy-from-resources! % dir)
                    ["cljsjs/react/common/react.ext.js"
                     "cljsjs/create-react-class/common/create-react-class.ext.js"
                     "cljsjs/react-dom/common/react-dom.ext.js"])
         :warning-handlers [on-warning]})
      (send-message! project-pane dir {:type :visual
                                       :warnings @warnings})
      (empty? @warnings)
      (catch Exception e
        (.printStackTrace e)
        (send-message! project-pane dir
          {:type :visual
           :warnings @warnings
           :exception (merge
                        {:message (.getMessage e)}
                        (select-keys (ex-data e) [:line :column]))})
        false))))

(defn run-main! [project-pane dir]
  (try
    (let [-main (resolve (symbol (lu/path->ns dir "server") "-main"))]
      (when (nil? -main)
        (throw (Exception. "Can't find a -main function in your server.clj file.")))
      (let [server (thunk-timeout #(with-security (-main)) 5000)]
        (when-not (instance? org.eclipse.jetty.server.Server server)
          (throw (Exception. "The -main function in server.clj must call run-jetty as its last step.")))
        server))
    (catch Exception e
      (.printStackTrace e)
      (send-message! project-pane dir
        {:type :visual-clj
         :exception {:message (.getMessage e)}})
      nil)))

(defn append! [webview s]
  (when (seq s)
    (-> webview
        .getEngine
        (.executeScript "window")
        (.call "append" (into-array [s])))))

(defn redirect-stdio! [logs-atom]
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
        (swap! logs-atom str s)))
    (lrepl/pipe-into-console! (:in-pipe stderr-pipes)
      (fn [s]
        (binding [*out* (java.io.OutputStreamWriter. System/out)]
          (println s))
        (swap! logs-atom str (str s \newline))))
    {:stdout stdout-pipes
     :stderr stderr-pipes}))

(defn init-server-logs! [inner-pane dir]
  (swap! runtime-state update-in [:projects dir]
    (fn [project]
      (let [logs (or (:server-logs-atom project)
                     (atom ""))
            pipes (redirect-stdio! logs)]
        (assoc project
          :server-logs-atom logs
          :server-logs-pipes pipes))))
  (swap! runtime-state update-in [:projects dir]
    (fn [{:keys [server-logs-atom] :as project}]
      (let [webview (.lookup inner-pane "#server_logs_webview")
            bridge (ui/init-console! webview false
                     (fn []
                       (append! webview @server-logs-atom)
                       (add-watch server-logs-atom :append
                         (fn [_ _ old-log new-log]
                           (Platform/runLater
                             #(append! webview (subs new-log (count old-log)))))))
                     (fn []))]
        (assoc project
          :server-logs-bridge bridge)))))

(defn init-reload-server! [dir]
  (let [reload-stop-fn (lr/start-reload-server! dir)
        reload-port (-> reload-stop-fn meta :local-port)
        f (io/file dir ".out" "lightmod.edn")]
    (doto f
      (-> .getParentFile .mkdirs)
      (spit (pr-str {:reload-port reload-port})))
    (swap! runtime-state update-in [:projects dir] assoc
      :reload-stop-fn reload-stop-fn
      :clients #{})))

(defn stop-server! [dir]
  (let [{:keys [server reload-stop-fn reload-file-watcher
                server-logs-atom server-logs-pipes]}
        (get-in @runtime-state [:projects dir])]
    (when server (.stop server))
    (when reload-stop-fn (reload-stop-fn))
    (when reload-file-watcher (hawk/stop! reload-file-watcher))
    (when server-logs-atom (remove-watch server-logs-atom :append))
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

(defn start-server! [project-pane dir]
  (when (and (compile-clj! project-pane dir)
             (compile-cljs! project-pane dir))
    (when-let [server (run-main! project-pane dir)]
      (let [port (-> server .getConnectors (aget 0) .getLocalPort)
            url (str "http://localhost:" port "/"
                  (-> dir io/file .getName)
                  "/index.html")
            out-dir (.getCanonicalPath (io/file dir ".out"))
            client-repl-started? (atom false)
            bridge (reify AppBridge
                     (onload [this]
                       (let [inner-pane (-> project-pane (.lookup "#project") .getItems (.get 1))]
                         (swap! runtime-state update-in [:projects dir]
                           ui/init-client-repl! inner-pane dir)
                         (swap! runtime-state update-in [:projects dir]
                           ui/init-server-repl! inner-pane dir)))
                     (onevalcomplete [this path results ns-name]
                       (if-not path
                         (let [inner-pane (-> project-pane (.lookup "#project") .getItems (.get 1))
                               result (-> results edn/read-string first)
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
                         (when-let [editor (get-in @runtime-state [:editor-panes path])]
                           (-> editor
                               (.lookup "#webview")
                               .getEngine
                               (.executeScript "window")
                               (.call "setInstaRepl" (into-array [results])))))))]
        (Platform/runLater
          (fn []
            (let [app (.lookup project-pane "#app")]
              (.setContextMenuEnabled app false)
              (.setOnStatusChanged (.getEngine app)
                (reify EventHandler
                  (handle [this event]
                    ; set the bridge
                    (-> (.getEngine app)
                        (.executeScript "window")
                        (.setMember "java" bridge)))))
              (-> app .getEngine (.load url)))))
        (swap! runtime-state update-in [:projects dir] merge
          {:url url
           :server server
           :app-bridge bridge
           :editor-file-watcher
           (or (get-in @runtime-state [:projects dir :editor-file-watcher])
               (e/create-file-watcher dir runtime-state))
           :reload-file-watcher
           (hawk/watch! [{:paths [dir]
                          :handler (fn [ctx {:keys [kind file]}]
                                     (when (and (= kind :modify)
                                                (some #(-> file .getName (.endsWith %)) [".clj" ".cljc"]))
                                       (compile-clj! project-pane dir (.getCanonicalPath file)))
                                     (cond
                                       (and (some #(-> file .getName (.endsWith %)) [".cljs" ".cljc"])
                                            (not (u/parent-path? out-dir (.getCanonicalPath file))))
                                       (compile-cljs! project-pane dir)
                                       (u/parent-path? out-dir (.getCanonicalPath file))
                                       (lr/reload-file! dir file))
                                     ctx)}])})))))

(defn stop-app! [project-pane dir]
  (stop-server! dir)
  (doto (.lookup project-pane "#app")
    (-> .getEngine (.loadContent "<html><body></body></html>"))))

(defn start-app! [project-pane dir]
  (stop-app! project-pane dir)
  (swap! runtime-state assoc-in [:projects dir :pane] project-pane)
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
                    (.start (Thread. #(start-server! project-pane dir))))
                  (onevalcomplete [this path results ns-name])))))))
    (-> app .getEngine (.load (str "http://localhost:"
                                (:web-port @runtime-state)
                                "/loading.html")))))

