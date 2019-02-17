(require
  '[nightlight.core :as nightlight]
  '[figwheel.main :as figwheel]
  '[[[name]].server :refer [-main]])

(let [server (-main)
      port (-> server meta :local-port)
      url (str "http://localhost:" port "/index.html")]
  (println "Started app on" url)
  (nightlight/start {:port 4000 :url url}))

(figwheel/-main "--build" "dev")

