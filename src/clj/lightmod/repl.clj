(ns lightmod.repl
  (:require [nightcode.utils :as u])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io PipedWriter PipedReader PrintWriter]))

(defn pipe-into-console! [in-pipe callback]
  (let [ca (char-array 4096)]
    (.start
      (Thread.
        (fn []
          (loop []
            (when-let [read (try (.read in-pipe ca)
                              (catch Exception _))]
              (when (pos? read)
                (let [s (u/remove-returns (String. ca 0 read))]
                  (callback s)
                  (Thread/sleep 50) ; prevent thread from being flooded
                  (recur))))))))))

(defn create-pipes []
  (let [out-pipe (PipedWriter.)
        in (LineNumberingPushbackReader. (PipedReader. out-pipe))
        pout (PipedWriter.)
        out (PrintWriter. pout)
        in-pipe (PipedReader. pout)]
    {:in in :out out :in-pipe in-pipe :out-pipe out-pipe}))

(defn start-repl-thread! [pipes start-ns callback]
  (let [{:keys [in-pipe in out]} pipes]
    (pipe-into-console! in-pipe callback)
    (.start
      (Thread.
        (fn []
          (binding [*out* out
                    *err* out
                    *in* in]
            (try
              (clojure.main/repl
                :init
                (fn []
                  (in-ns start-ns))
                :read
                (fn [request-prompt request-exit]
                  (let [form (clojure.main/repl-read request-prompt request-exit)]
                    (if (= 'lightmod.repl/exit form) request-exit form))))
              (catch Exception e (some-> (.getMessage e) println))
              (finally (println "=== Finished ==="))))))))
  pipes)

