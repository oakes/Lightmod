(ns lightmod.init
  (:require [clojure.java.io :as io]))

(defmacro read-logo []
  (slurp (io/resource "clojure.svg")))

