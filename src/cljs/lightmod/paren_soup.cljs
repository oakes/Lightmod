(ns lightmod.paren-soup
  (:require [nightcode.paren-soup]
            [paren-soup.instarepl :as ir]
            [cljs.reader :refer [read-string]]
            [goog.object :as gobj]))

(defn set-instarepl [results]
  (let [results (mapv
                  ; paren-soup expects errors to be JS arrays
                  #(if (vector? %) (into-array %) %)
                  (read-string results))
        instarepl (.querySelector js/document "#instarepl")
        content (.querySelector js/document "#content")
        elems (ir/get-collections content)
        locations (ir/elems->locations elems (.-offsetTop instarepl))]
    (when (= (count results) (count locations))
      (-> instarepl
          .-innerHTML
          (set! (ir/results->html results locations))))))

(gobj/set js/window "setInstaRepl" set-instarepl)

; don't use default compiler-fn
(set! ir/create-compiler-fn (fn []))

