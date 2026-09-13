(ns glimmer-appkit.counter
  "A reactive counter — the canonical reagent-style demo over AppKit.

  Local state lives in a reactive atom created once (Form-2 component). Clicking
  the - button swaps the atom; glimmer re-renders just this component and the
  label widget is updated in place. Try `jolt counter` (the :counter task)."
  (:require [glimmer.ratom :as r :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-appkit.core]))   ; installs the AppKit backend

(defn counter
  "Form-2: outer fn creates state once, inner fn renders."
  []
  (let [count (atom 0)]
    (fn []
      [:vbox {:spacing 12}
       [:label {:label (str "Count: " @count)}]
       [:hbox {:spacing 8}
        [:button {:label "− 1" :on-click #(swap! count dec)}]
        [:button {:label "+ 1" :on-click #(swap! count inc)}]
        [:button {:label "reset" :on-click #(reset! count 0)}]]])))

(defn -main [& _]
  (ui/run counter :title "glimmer counter" :width 320 :height 160))
