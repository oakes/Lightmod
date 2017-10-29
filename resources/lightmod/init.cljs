(ns lightmod.init
  (:require [adzerk.boot-reload.client :as client]
            [adzerk.boot-reload.reload :as rl]
            [adzerk.boot-reload.display :as d]
            [goog.dom :as dom]
            [eval-soup.core :as es]
            [goog.object :as gobj]
            [cljs.reader :refer [read-string]])
  (:import goog.net.XhrIo)
  (:require-macros [lightmod.init :as i]))

(def clj-logo (i/read-logo))

(defn construct-hud-node [{:keys [type warnings exception] :as messages}]
  (doto (d/mk-node :div (d/style :pad :flex :flex-c (cond
                                                      exception      :bg-red
                                                      (seq warnings) :bg-yellow
                                                      :else          :bg-clear)))
    (dom/append (d/mk-node :div (d/style :logo :mr10)
                  (d/logo-node (if (= type :visual-clj) clj-logo d/cljs-logo))))
    (dom/append (cond exception      (d/exception-node exception)
                      (seq warnings) (d/warnings-node warnings)
                      :else          (d/reloaded-node)))))

(set! d/construct-hud-node construct-hud-node)

(defmethod client/handle :visual-clj
  [state opts]
  (when (rl/has-dom?)
    (d/display state opts)))

(set! (.-onload js/window)
  (fn []
    (.send XhrIo
      ".out/reload-port.txt"
      (fn [e]
        (if (.isSuccess (.-target e))
          (client/connect (str "ws://localhost:" (.. e -target getResponseText))
            {:on-jsload +})
          (js/console.log "WARNING: Couldn't find reload port")))
      "GET")))

(defn form->serializable [form]
  (if (instance? js/Error form)
    (array (or (some-> form .-cause .-message) (.-message form))
      (.-fileName form) (.-lineNumber form))
    (pr-str form)))

(def current-ns (atom 'cljs.user))

(defn ^:export eval-code [path code]
  (es/code->results
    (read-string code)
    (fn [results]
      (.onevalcomplete js/window.java
        path
        (pr-str (mapv form->serializable results))
        (str @current-ns)))
    {:current-ns current-ns
     :custom-load (fn [opts cb]
                    (cb {:lang :clj :source ""}))}))

