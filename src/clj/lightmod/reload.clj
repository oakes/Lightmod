(ns lightmod.reload
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nightcode.state :refer [pref-state runtime-state]]
            [nightcode.utils :as u]
            [org.httpkit.server :as http]))

(defn web-path
  ([rel-path] (web-path {} rel-path))
  ([opts rel-path]
   ; windows fix, convert \ characters to / in rel-path
   (let [rel-path (str/replace rel-path #"\\" "/")
         {:keys [target-path asset-path cljs-asset-path]} opts]
     {:canonical-path (.getCanonicalPath (io/file target-path rel-path))
      :web-path (str
                  cljs-asset-path "/"
                  (str/replace rel-path
                                  (re-pattern (str "^" (str/replace (or asset-path "") #"^/" "") "/"))
                                  ""))})))

(defn connect! [dir channel]
  (swap! runtime-state update-in [:projects dir :clients] conj channel)
  (http/on-close channel
    (fn [_] (swap! runtime-state update-in [:projects dir :clients] disj channel))))

(defn reload-handler [dir request]
  (if-not (:websocket? request)
    {:status 501 :body "Websocket connections only."}
    (http/with-channel request channel (connect! dir channel))))

(defn send-changed!
  ([dir changed] (send-changed! dir {} changed))
  ([dir opts changed]
   (when-let [clients (get-in @runtime-state [:projects dir :clients])]
     (doseq [channel clients]
       (http/send! channel
         (pr-str {:type :reload
                  :files (map #(web-path opts %) changed)}))))))

(defn send-message! [dir messages]
  (when-let [clients (get-in @runtime-state [:projects dir :clients])]
    (doseq [channel clients]
      (http/send! channel (pr-str messages)))))

(defn start-reload-server! [dir]
  (http/run-server (partial reload-handler dir) {:port 0}))

(defn reload-file! [dir file]
  (->> file
       .getCanonicalPath
       (u/get-relative-path dir)
       io/file
       hash-set
       (send-changed! dir)))

