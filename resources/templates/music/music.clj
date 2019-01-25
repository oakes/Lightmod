(ns [[name]].music
  (:require [edna.core :as edna]))

(def music
  [:organ {:octave 4
           :tempo 74}
   
   1/8 #{:-d :-a :e :f#} :a 1/2 #{:f# :+d}
   1/8 #{:-e :e :+c} :a 1/2 #{:c :e}
   
   1/8 #{:-d :-a :e :f#} :a :+d :+c# :+e :+d :b :+c#
   1/2 #{:-e :c :a} 1/2 #{:c :e}])

(defn generate* []
  (-> music
      (edna/export! {:type :wav})
      .toByteArray))

(def generate (memoize generate*))

