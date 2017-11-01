(ns lightmod.loading
  (:require [lightmod.init]
            [reagent.core :as r]
            [cljs-react-material-ui.core :refer [get-mui-theme]]
            [cljs-react-material-ui.reagent :as ui]
            [goog.object :as gobj]
            [cljs.reader :refer [read-string]]
            [adzerk.boot-reload.client :as client]))

(def error? (r/atom false))

(defn ^:export show-error [msg]
  (let [msg (read-string msg)]
    (when (or (:exception msg)
              (seq (:warnings msg)))
      (reset! error? true))
    (client/handle msg nil)))

(defn app []
  [ui/mui-theme-provider
   {:mui-theme (get-mui-theme (gobj/get js/MaterialUIStyles "LightRawTheme"))}
   (let [style {:position :fixed
                :left "50%"
                :top "50%"
                :transform "translate(-50%, -50%)"}]
     (if @error?
       [:div {:style style}
        [:p "Encountered an error."]
        [:p "Please fix it and press Restart."]]
       [ui/circular-progress {:size 100 :style style}]))])

(r/render-component [app] (.querySelector js/document "#app"))

