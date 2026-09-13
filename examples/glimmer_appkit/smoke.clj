(ns glimmer-appkit.smoke
  "Non-interactive smoke test: mount the counter demo into a real AppKit window
  and auto-quit after 1.2s. Exit 0 only if the whole pipeline (framework load,
  app loop, widget mount, signal wiring, clean shutdown) ran without throwing."
  (:require [glimmer-appkit.counter :as counter]
            [glimmer.core :as ui]
            [glimmer-appkit.core]))   ; installs the AppKit backend

(defn -main [& _]
  (try
    (ui/run counter/counter
            :title "glimmer smoke" :width 320 :height 160
            :auto-quit-ms 1200)
    (println "SMOKE OK")
    (catch :default e
      (println "SMOKE FAIL:" e)
      (let [exit (resolve 'jolt.host/exit)]
        (when exit (exit 1))))))
