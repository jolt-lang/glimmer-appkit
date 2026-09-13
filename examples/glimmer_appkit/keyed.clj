(ns glimmer-appkit.keyed
  "Non-interactive proof that glimmer's keyed reconciliation reuses widgets across
  add/remove/reorder instead of recreating them by position. Mounts a vbox of
  labels keyed by a stable :id, then mutates the backing list — reverse, remove a
  middle item, insert at the front — and after each mutation checks two things
  against the live AppKit tree:

    1. surviving keys keep the SAME widget pointer (reuse, not recreate), so a
       row's signal handlers and local state would follow its key; and
    2. the stack's actual arranged-subview order matches the requested order.

  Reconciliation is synchronous (a ratom write notifies watchers inline), so each
  mutation is fully reconciled by the time we assert. The whole check runs as one
  scheduled loop job, then quits. Run via the :keyed task; needs a GUI session.
  Exits non-zero on any failed check."
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-appkit.core :as appkit]
            [glimmer-appkit.widget :as w]))

(def items (atom [{:id :a :text "alpha"}
                  {:id :b :text "bravo"}
                  {:id :c :text "charlie"}]))

(def failures (atom []))
(def result (atom :pending))

;; Rows are COMPONENTS (not native elements) so the smoke covers keyed
;; reconciliation of component children — whose contributed widget lives one
;; level down on the expanded child. The expansion is a box WITH a child (not a
;; bare label) so destroying a row exercises the recurse-through-component path
;; without re-removing grandchildren from an already-finalized box.
(defn- row [text]
  [:hbox {:spacing 4 :halign :start}
   [:label {:label text}]])

(defn app []
  (into [:vbox {:spacing 6 :margin 16}]
        (for [{:keys [id text]} @items]
          [row {:key id} text]))) ; keyed by :id

;; --- reading the live tree ---------------------------------------------------
;; The widget an instance contributes: its own :widget, or (for a component) the
;; widget of its single expanded child. Mirrors glimmer.core/inst-widget.
(defn- inst-widget [a]
  (let [i @a] (or (:widget i) (some-> (first (:children i)) inst-widget))))

;; The mounted vbox lives one level under the root component (a component owns no
;; widget of its own — its single child atom holds the expanded native vbox).
(defn- vbox-atom [root] (first (:children @root)))
(defn- vbox-widget [root] (inst-widget (vbox-atom root)))

;; key -> widget pointer, read from the reconciler's own instance tree.
(defn- key->widget [root]
  (into {} (map (fn [a] [(:key @a) (inst-widget a)]) (:children @(vbox-atom root)))))

;; --- assertions --------------------------------------------------------------
(defn- record! [ok? label]
  (when-not ok? (swap! failures conj label)))

(defn- check-order!
  "The stack's actual arranged-subview order matches `ids` (the requested item
  order), and each id still maps to `expected`'s widget pointer (reuse, not
  recreate)."
  [root ids expected label]
  (let [now (key->widget root)]
    (record! (= (w/stack-children (vbox-widget root))
                (mapv now ids))
             (str label " :order"))
    (doseq [id ids :when (contains? expected id)]
      (record! (= (now id) (expected id))
               (str label " :reuse " id)))))

(defn- checkbutton-suppression!
  "A programmatic :active change (the re-render after a bulk op like \"complete
  all\") must NOT fire :on-toggled, or each row's handler would flip the task
  straight back. AppKit does not fire actions for programmatic setState:, so this
  should hold with zero suppression machinery."
  []
  (let [hits (atom 0)
        cb   (w/create! :checkbutton {:active false :on-toggled (fn [] (swap! hits inc))})]
    (w/apply-props! :checkbutton cb {:active true})
    (w/apply-props! :checkbutton cb {:active false})
    (w/apply-props! :checkbutton cb {:active true})
    (record! (zero? @hits) (str "checkbutton-suppress fired=" @hits))))

(defn- run-checks! [root]
  ;; Re-renders are asynchronous (each ratom write queues a re-render job on the
  ;; loop), so each mutation below schedules the NEXT step as a fresh loop job:
  ;; the re-render drains before the next check runs, and the tree is settled.
  (let [w0 (key->widget root)]
    (check-order! root [:a :b :c] w0 "baseline")
    (letfn [(step [i]
              (appkit/schedule!
                (fn []
                  (case i
                    0 (do (reset! items [{:id :c :text "charlie"}
                                         {:id :b :text "bravo"}
                                         {:id :a :text "alpha"}])
                          (step 1))
                    1 (do (check-order! root [:c :b :a] w0 "reverse")
                          (reset! items [{:id :c :text "charlie"}
                                         {:id :a :text "alpha"}])
                          (step 2))
                    2 (do (check-order! root [:c :a] w0 "remove")
                          (record! (not (contains? (key->widget root) :b))
                                   "remove :b-gone")
                          (reset! items [{:id :d :text "delta"}
                                         {:id :c :text "charlie"}
                                         {:id :a :text "alpha"}])
                          (step 3))
                    3 (do (check-order! root [:d :c :a] w0 "insert")
                          (let [now (key->widget root)]
                            (record! (contains? now :d) "insert :d-present")
                            (record! (not (contains? w0 (now :d))) "insert :d-is-new"))
                          (checkbutton-suppression!)
                          (reset! result (if (empty? @failures) :pass :fail))
                          (appkit/quit!))))))]
      (step 0))))

(defn- driver [root]
  (appkit/schedule!
    (fn []
      (try (run-checks! root)
           (reset! result (if (empty? @failures) :pass :fail))
           (catch :default e
             (reset! result :fail)
             (swap! failures conj (str "threw: " e))))
      (appkit/quit!))))

(defn -main [& _]
  (try
    (appkit/schedule!   ; first drain warms the loop; the driver fires next
      (fn [] (driver (appkit/root-inst))))
    (ui/run app :title "keyed smoke" :width 240 :height 180 :auto-quit-ms 5000)
    (prn :keyed :result @result :failures @failures)
    (when (not= :pass @result)
      (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1))))
    (catch :default e
      (prn :keyed-error e)
      (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1))))))
