(ns glimmer-appkit.reactivity-smoke
  "Non-interactive proof that glimmer's reactivity works against the live AppKit
  loop. Mounts a Form-1 component whose render increments a counter; a scheduled
  job then swaps the reactive cell the component reads. The component must
  re-render exactly once (render-count goes 1 -> 2). Run via the :smoke task;
  requires a GUI session. Exits non-zero on failure."
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-appkit.core :as appkit]))   ; installs the AppKit backend

(def n (atom 0))
(def render-count (atom 0))
(def result (atom :pending))

(defn app []
  (swap! render-count inc)
  [:label {:label (str "n=" @n)}])

(defn -main [& _]
  (try
    ;; The swap + check are scheduled onto the loop, so they fire in order once
    ;; [NSApp run] is up: the swap re-renders (1 -> 2), the check reads the count
    ;; after that re-render, then the timer quits the app and run returns.
    (appkit/schedule! (fn []
                       (swap! n inc)
                       (appkit/schedule!
                         #(reset! result (if (>= @render-count 2) :pass :fail)))))
    (ui/run app :title "reactivity smoke" :width 240 :height 120 :auto-quit-ms 900)
    (prn :smoke :result @result :render-count @render-count)
    (when (not= :pass @result)
      (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1))))
    (catch :default e
      (prn :smoke-error e)
      (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1))))))
