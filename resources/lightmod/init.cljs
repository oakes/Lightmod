(ns lightmod.init
  (:require [adzerk.boot-reload.client :as client])
  (:import goog.net.XhrIo))

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

