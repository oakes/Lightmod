(ns [[name]].client
  (:require [play-cljs.core :as p]
            [[[name]].state :as s]
            [[[name]].utils :as u]
            [goog.events :as events]))

(defonce game (p/create-game js/window.innerWidth js/window.innerHeight))
(defonce state (atom {}))

(def main-screen
  (reify p/Screen
    (on-show [_]
      (reset! state (s/initial-state game)))
    (on-hide [_])
    (on-render [this]
      (let [{:keys [x y current direction]} @state]
        (p/render game [[:stroke {}
                         [:fill {:color "lightblue"}
                          [:rect {:x 0 :y 0 :width js/window.innerWidth :height js/window.innerHeight}]]]
                        [:tiled-map {:name u/map-name :x x}]
                        [:div {:x (u/get-offset game) :y y :width u/player-width :height u/player-height}
                         current]])
        (when (> y (- (p/get-height game) u/player-height))
          (p/set-screen game this)))
      (reset! state
        (-> @state
            (s/move game)
            (s/prevent-move game)
            (s/animate))))))

(events/listen js/window "resize"
  (fn [event]
    (p/set-size game js/window.innerWidth js/window.innerHeight)))

(doto game
  (p/start)
  (p/set-screen main-screen))

