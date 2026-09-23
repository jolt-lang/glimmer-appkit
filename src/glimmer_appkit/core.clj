(ns glimmer-appkit.core
  "The AppKit backend for glimmer (the macOS native toolkit). Requiring this
  namespace installs it, after which glimmer's portable reconciler (glimmer.core)
  renders hiccup into real AppKit views:

    (ns myapp
      (:require [glimmer.ratom :refer [atom]]
                [glimmer.core :as ui]
                [glimmer-appkit.core]))          ; installs the AppKit backend

    (defn -main [& _] (ui/run my-app :title \"hello\"))

  What this namespace supplies to glimmer.backend is the toolkit half of the
  seam: element creation and prop application (glimmer-appkit.widget), container
  child management, the app loop, and the marshalling of off-thread work onto
  that loop.

  The app loop is NSApplication; run! builds a window, mounts the root
  component into it, and calls [NSApp run], which blocks running the AppKit
  main loop. Every event handler is a :collect-safe foreign-callable invoked
  from inside that loop. Off-thread re-renders land via a CFRunLoopSource
  registered on the main run loop — the AppKit analogue of GTK's g_idle_add,
  without needing libdispatch or blocks (a source's perform callback is a plain
  C function pointer)."
  (:require [glimmer.backend :as b]
            [glimmer-appkit.ffi :as u]
            [glimmer-appkit.widget :as w]
            [jolt.ffi :as ffi]))

;; --- marshalling work onto the AppKit main loop ------------------------------
;; While [NSApp run] blocks the main thread, a ratom mutation made off that
;; thread (an nREPL eval on its worker thread, or any future) would otherwise
;; reconcile — calling AppKit — off the main thread, which AppKit rejects.
;; glimmer.core defers those re-renders through backend/schedule, which lands
;; here. A single CFRunLoopSource on the main run loop drains a queue of thunks;
;; posting is signal+wakeup, so no per-post allocation happens.
(defonce ^:private scheduler
  (let [queue   (atom [])
        perform (ffi/foreign-callable
                  (fn [_info]
                    (let [jobs @queue]
                      (reset! queue [])
                      (run! (fn [f]
                              (try (f)
                                   (catch :default e
                                     (println "glimmer-appkit: scheduled work failed:" e))))
                            jobs)))
                  [:pointer] :void :collect-safe)
        ;; CFRunLoopSourceContext on arm64 (all 8-byte fields):
        ;; version@0 info@8 retain@16 release@24 copyDescription@32
        ;; equal@40 hash@48 schedule@56 cancel@64 perform@72
        ctx (ffi/alloc 80)]
    (doseq [off [0 8 16 24 32 40 48 56 64]] (ffi/write ctx :pointer 0 off))
    (ffi/write ctx :pointer perform 72)
    (let [src (u/cf-run-loop-source-create ffi/null 0 ctx)
          rl  (u/cf-run-loop-get-main)]
      (u/cf-run-loop-add-source rl src (u/default-mode))
      {:queue queue :source src :run-loop rl})))

(defn- post-to-gui
  "Schedule zero-arg `work` on the AppKit main loop."
  [work]
  (let [{:keys [queue source run-loop]} scheduler]
    (swap! queue conj work)
    (u/cf-run-loop-source-signal source)
    (u/cf-run-loop-wake-up run-loop))
  nil)

(defn schedule!
  "Schedule zero-arg `f` on the AppKit main loop. glimmer's re-renders take
  this route when loop-running?; exposed so examples can drive the loop."
  [f]
  (post-to-gui f)
  nil)

(defn quit!
  "Stop the running NSApplication (used by examples instead of :auto-quit-ms
  when the app must end on a specific event). Posts a wake-up event so the
  stop takes effect."
  []
  (let [app (u/shared-application)]
    (u/stop-app! app)
    (u/post-event-at-start! app (u/application-defined-event)))
  nil)

(defonce ^:private current-mount (atom nil))

(defn root-inst
  "The mounted root instance atom of the currently running app (nil before any
  run). Lets an external driver — a smoke test, or an nREPL session — reach the
  live tree through glimmer's own instance hierarchy."
  []
  (:inst @current-mount))

;; --- the app loop ------------------------------------------------------------
(defn- run*
  "Build the NSApplication and a window, mount the root component into it,
  present it, and run the AppKit main loop. Blocks until the app quits (window
  closed, or :auto-quit-ms). backend/loop-running? is set for the duration so
  reactive changes marshal onto the loop instead of rendering inline on
  whatever thread wrote the cell."
  [opts mount-root!]
  (let [{:keys [title width height auto-quit-ms]
         :or {title "glimmer" width 400 height 300}} opts
        _    (u/objc-autorelease-pool-push)
        _    (u/install-uncaught-exception-report!)
        app  (u/shared-application)
        _    (u/set-activation-policy! app u/ACTIVATION-REGULAR)
        _    (u/set-app-delegate! app (w/invoker))
        win  (u/window-new title width height)
        _    (reset! current-mount (mount-root! win :window))
        _    (u/window-center! win)
        _    (u/window-show! win)
        _    (u/activate! app)
        _    (when auto-quit-ms (w/auto-quit! app auto-quit-ms))]
    (reset! b/loop-running? true)
    (try
      (u/run-app! app)
      (finally (reset! b/loop-running? false)))))

(defn- run!
  "Backend entry point for glimmer.core/run.

  AppKit requires its event loop on the process main thread. Under
  `jolt nrepl-server` the primordial thread parks in jolt.host/park-until-interrupt
  (a main-thread pump); this hops the boot onto it ASYNCHRONOUSLY via
  jolt.host/call-on-main-thread-async and returns right away, so the nREPL eval
  that started the app completes and the session stays live for reactive edits
  (swap! a ratom to re-render, or redefine components and call
  glimmer.core/reload! to re-render the running window in place — both marshal
  onto the main loop). Under `jolt run` (or any non-jolt host) there is no pump,
  so it runs inline and blocks until the app quits."
  [opts mount-root!]
  (let [start (fn [] (run* opts mount-root!))]
    (if-let [hop (resolve 'jolt.host/call-on-main-thread-async)]
      (hop start)
      (start))))

;; --- the backend -------------------------------------------------------------
(def backend
  "The AppKit backend map handed to glimmer.backend/register!. See that
  namespace for the contract each key satisfies."
  {:name           :appkit
   :create!        w/create!
   :apply-props!   w/apply-props!
   :append-child!  w/append-child!
   :remove-child!  w/remove-child!
   :replace-child! w/replace-child!
   :reorder-child! w/reorder-child!
   :schedule       post-to-gui
   :run            run!})

(defn install!
  "Make AppKit the backend glimmer renders with. Called on load, so requiring
  this namespace is enough; exposed for code that wants to be explicit, or to
  switch back after another backend was installed."
  []
  (b/register! backend)
  nil)

(defonce ^:private _installed (do (install!) true))
