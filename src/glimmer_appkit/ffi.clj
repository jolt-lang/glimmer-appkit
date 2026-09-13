(ns glimmer-appkit.ffi
  "Raw bindings for the Objective-C runtime and AppKit, built on jolt.ffi.

  Objective-C from a C FFI: classes come from objc_getClass, selectors from
  sel_registerName, and every method call goes through objc_msgSend bound at a
  concrete arity. On Apple arm64 objc_msgSend is a dispatcher that forwards the
  caller's registers to the method implementation, so a FIXED-arity binding
  whose struct args are flattened into their component doubles matches the real
  call layout (a CGRect is an HFA of 4 doubles -> d0-d3, exactly like four
  separate doubles). Never bind it with :varargs — the variadic convention
  passes FP args on the stack, which corrupts the registers a real method
  expects. Verified on this machine: string/pointer/double args, NSUInteger /
  BOOL / const-char* returns, and flattened NSRect/NSPoint args all round-trip.

  AppKit.framework is dlopen'd here on macOS so its classes register
  (objc_getClass only finds classes in loaded frameworks). On other platforms
  the load is skipped and only the headless helpers (markup, with-orientation
  in glimmer-appkit.widget) are usable — which is what the CI unit tests need.

  Marshalling: a C string crosses the FFI as :string (UTF-8); NSStrings are
  created with stringWithUTF8String: and read back with UTF8String. BOOL is
  :char, NSInteger/NSUInteger :int64, CGFloat :double."
  (:require [jolt.ffi :as ffi]))

;; --- load AppKit on macOS so its classes register ----------------------------
(defn- macos? [] (= (System/getProperty "os.name") "Mac OS X"))
(when (macos?)
  (try
    (ffi/load-library "/System/Library/Frameworks/AppKit.framework/AppKit")
    (ffi/load-library "/System/Library/Frameworks/Foundation.framework/Foundation")
    (catch :default _
      (println "glimmer-appkit: could not load AppKit.framework; headless helpers only"))))

;; --- constants ---------------------------------------------------------------
;; NSWindowStyleMask: titled | closable | miniaturizable | resizable
(def WINDOW-STYLE 15)
(def NS-BACKING-BUFFERED 2)
;; NSApplicationActivationPolicy
(def ACTIVATION-REGULAR 0)
;; NSUserInterfaceLayoutOrientation
(def ORIENTATION-HORIZONTAL 0)
(def ORIENTATION-VERTICAL 1)
;; NSLayoutAttribute
(def ATTR-LEFT 1)  (def ATTR-RIGHT 2)  (def ATTR-TOP 3) (def ATTR-BOTTOM 4)
(def ATTR-LEADING 5) (def ATTR-TRAILING 6)
(def ATTR-WIDTH 7) (def ATTR-HEIGHT 8)
(def ATTR-CENTER-X 9) (def ATTR-CENTER-Y 10)
(def RELATION-EQUAL 0)
;; NSStackViewDistribution
(def DISTRIBUTION-GRAVITY -1)
(def DISTRIBUTION-FILL 0)
(def DISTRIBUTION-FILL-EQUALLY 1)
;; NSTextAlignment
(def TEXT-ALIGN-LEFT 0) (def TEXT-ALIGN-CENTER 1) (def TEXT-ALIGN-RIGHT 2)
;; NSLineBreakMode
(def LINE-BREAK-WRAP 0) (def LINE-BREAK-CLIP 2)
(def LINE-BREAK-HEAD 3) (def LINE-BREAK-TAIL 4) (def LINE-BREAK-MIDDLE 5)
;; NSBoxType / NSTitlePosition
(def BOX-SEPARATOR 2)
(def TITLE-POSITION-AT-TOP 0)
;; NSTextFieldBezelStyle
(def BEZEL-ROUNDED 1)
;; NSControlStateValue
(def STATE-OFF 0) (def STATE-ON 1)
;; NSEventType
(def EVENT-APPLICATION-DEFINED 15)
;; NSLayoutPriority
(def PRIORITY-REQUIRED 1000)
(def PRIORITY-LOW 250)
(def PRIORITY-VERY-LOW 1)
;; NSAttributedString attribute keys — the constants ARE these strings
(def NS-FONT-ATTR "NSFont")
(def NS-FOREGROUND-COLOR-ATTR "NSColor")
(def NS-STRIKETHROUGH-ATTR "NSStrikethrough")
(def NS-STRIKETHROUGH-COLOR-ATTR "NSStrikethroughColor")
(def NS-UNDERLINE-ATTR "NSUnderline")

;; --- ObjC runtime ------------------------------------------------------------
;; objc_msgSend bound at the arities the backend needs. Each is a distinct
;; foreign-procedure over the same symbol; the dispatcher forwards registers
;; verbatim, so the typed shape only has to match the method's calling
;; convention, not its declared prototype.
(ffi/defcfn objc-msg-send-0        "objc_msgSend" [:pointer :pointer] :pointer)
(ffi/defcfn objc-msg-send-0void    "objc_msgSend" [:pointer :pointer] :void)
(ffi/defcfn objc-msg-send-0i64     "objc_msgSend" [:pointer :pointer] :int64)
(ffi/defcfn objc-msg-send-0d       "objc_msgSend" [:pointer :pointer] :double)
(ffi/defcfn objc-msg-send-0char    "objc_msgSend" [:pointer :pointer] :char)
(ffi/defcfn objc-msg-send-0cstr    "objc_msgSend" [:pointer :pointer] :string)
(ffi/defcfn objc-msg-send-1p       "objc_msgSend" [:pointer :pointer :pointer] :pointer)
(ffi/defcfn objc-msg-send-1pvoid   "objc_msgSend" [:pointer :pointer :pointer] :void)
(ffi/defcfn objc-msg-send-1pchar   "objc_msgSend" [:pointer :pointer :pointer] :char)
(ffi/defcfn objc-msg-send-1s       "objc_msgSend" [:pointer :pointer :string] :pointer)
(ffi/defcfn objc-msg-send-1d       "objc_msgSend" [:pointer :pointer :double] :pointer)
(ffi/defcfn objc-msg-send-1dvoid   "objc_msgSend" [:pointer :pointer :double] :void)
(ffi/defcfn objc-msg-send-1i64     "objc_msgSend" [:pointer :pointer :int64] :pointer)
(ffi/defcfn objc-msg-send-1i64void "objc_msgSend" [:pointer :pointer :int64] :void)
;; BOOL args are :int — jolt's :char is a Scheme CHARACTER, not an 8-bit int
;; (the GTK backend used :int for the same reason). BOOL returns stay :char.
(ffi/defcfn objc-msg-send-1intvoid "objc_msgSend" [:pointer :pointer :int] :void)
(ffi/defcfn objc-msg-send-1d1i64void "objc_msgSend" [:pointer :pointer :double :int64] :void)
;; setContentHuggingPriority:forOrientation: — NSLayoutPriority is a FLOAT
(ffi/defcfn objc-msg-send-1f1i64void "objc_msgSend" [:pointer :pointer :float :int64] :void)
;; setPriority: (NSLayoutConstraint) — NSLayoutPriority is a FLOAT
(ffi/defcfn objc-msg-send-1fvoid "objc_msgSend" [:pointer :pointer :float] :void)
(ffi/defcfn objc-msg-send-2dvoid   "objc_msgSend" [:pointer :pointer :double :double] :void)
(ffi/defcfn objc-msg-send-4d       "objc_msgSend" [:pointer :pointer :double :double :double :double] :pointer)
(ffi/defcfn objc-msg-send-4dvoid   "objc_msgSend" [:pointer :pointer :double :double :double :double] :void)
;; window init: (id, SEL, CGRect as 4 doubles, NSUInteger style, NSInteger backing, BOOL defer)
(ffi/defcfn objc-msg-send-4d3
  "objc_msgSend" [:pointer :pointer :double :double :double :double :int64 :int64 :int] :pointer)
;; button/checkbox factories: (id, SEL, NSString* title, id target, SEL action)
(ffi/defcfn objc-msg-send-3p "objc_msgSend" [:pointer :pointer :pointer :pointer :pointer] :pointer)
(ffi/defcfn objc-msg-send-1p1i64void "objc_msgSend" [:pointer :pointer :pointer :int64] :void)
;; (id, SEL, id) -> NSInteger (indexOfArrangedSubview:)
(ffi/defcfn objc-msg-send-1p-i64ret "objc_msgSend" [:pointer :pointer :pointer] :int64)
;; addAttribute:value:range: — (id, SEL, id key, id value, NSUInteger loc, NSUInteger len)
(ffi/defcfn objc-msg-send-2p2i64void "objc_msgSend" [:pointer :pointer :pointer :pointer :int64 :int64] :void)
;; performSelectorOnMainThread:withObject:waitUntilDone:
(ffi/defcfn objc-msg-send-3pchar "objc_msgSend" [:pointer :pointer :pointer :pointer :int] :void)
;; scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:
(ffi/defcfn objc-msg-send-1d3pchar
  "objc_msgSend" [:pointer :pointer :double :pointer :pointer :pointer :int] :pointer)
;; postEvent:atStart:
(ffi/defcfn objc-msg-send-1pcharvoid "objc_msgSend" [:pointer :pointer :pointer :int] :void)
;; constraintWithItem:attribute:relatedBy:toItem:attribute:multiplier:constant:
(ffi/defcfn objc-msg-send-constraint
  "objc_msgSend" [:pointer :pointer :pointer :int64 :int64 :pointer :int64 :double :double] :pointer)
;; otherEventWithType:location:modifierFlags:timestamp:windowNumber:context:subtype:data1:data2:
(ffi/defcfn objc-msg-send-other-event
  "objc_msgSend"
  [:pointer :pointer :int64 :double :double :int64 :double :int64 :pointer :int :int64 :int64] :pointer)
;; [NSApp run] blocks for the app's lifetime — :blocking so it won't pin the GC.
(ffi/defcfn objc-msg-send-run "objc_msgSend" [:pointer :pointer] :void :blocking)

(ffi/defcfn objc-get-class           "objc_getClass"           [:string] :pointer)
(ffi/defcfn sel-register-name        "sel_registerName"        [:string] :pointer)
(ffi/defcfn objc-allocate-class-pair "objc_allocateClassPair"  [:pointer :string :size_t] :pointer)
(ffi/defcfn objc-register-class-pair "objc_registerClassPair"  [:pointer] :void)
(ffi/defcfn class-add-method         "class_addMethod"         [:pointer :pointer :pointer :string] :char)
(ffi/defcfn objc-autorelease-pool-push "objc_autoreleasePoolPush" [] :pointer)
(ffi/defcfn objc-autorelease-pool-pop  "objc_autoreleasePoolPop"  [:pointer] :void)

;; --- CFRunLoop (scheduling onto the main loop, pure C — no blocks needed) ----
(ffi/defcfn cf-run-loop-get-main      "CFRunLoopGetMain"      [] :pointer)
(ffi/defcfn cf-run-loop-source-create "CFRunLoopSourceCreate" [:pointer :int64 :pointer] :pointer)
(ffi/defcfn cf-run-loop-add-source    "CFRunLoopAddSource"    [:pointer :pointer :pointer] :void)
(ffi/defcfn cf-run-loop-remove-source "CFRunLoopRemoveSource" [:pointer :pointer :pointer] :void)
(ffi/defcfn cf-run-loop-contains-source "CFRunLoopContainsSource" [:pointer :pointer :pointer] :char)
(ffi/defcfn cf-run-loop-run-in-mode
  "CFRunLoopRunInMode" [:pointer :double :int] :int)
(ffi/defcfn cf-run-loop-source-signal "CFRunLoopSourceSignal" [:pointer] :void)
(ffi/defcfn cf-run-loop-wake-up       "CFRunLoopWakeUp"       [:pointer] :void)
;; CFRunLoopAddSource hashes the mode string and does NOT accept NULL (unlike
;; CFRunLoopAddTimer, whose docs bless NULL as "default mode"). Create the
;; mode string once instead of relying on a global we can't bind. Note the
;; special kCFRunLoopCommonModes constant must NOT be reproduced this way —
;; CF recognizes that one by identity and a value-equal copy doesn't get added
;; to the loop's common modes — so use the default mode, which is where
;; [NSApp run] runs.
(ffi/defcfn cf-string-create-with-cstring
  "CFStringCreateWithCString" [:pointer :string :int64] :pointer)

(def ^:private kCFRunLoopDefaultMode
  ;; kCFStringEncodingUTF8. A delay, not a value: creating the string is a
  ;; foreign call, and a foreign procedure resolves its entry the first time it
  ;; runs. Made at load, this was the one call that ran on every platform --
  ;; requiring the namespace on Linux raised "no entry for
  ;; CFStringCreateWithCString" before any macOS guard could matter.
  (delay (cf-string-create-with-cstring ffi/null "kCFRunLoopDefaultMode" 134217984)))

(defn default-mode
  "The CFString naming the run loop's default mode (retained by the loop)."
  [] @kCFRunLoopDefaultMode)

;; --- selector / class caches -------------------------------------------------
(def ^:private sel-cache (atom {}))
(defn sel
  "Register (once) and return the selector for a method name."
  [name]
  (or (get @sel-cache name)
      (let [s (sel-register-name name)]
        (swap! sel-cache assoc name s)
        s)))

(def ^:private class-cache (atom {}))
(defn cls
  "Look up (once) and return the ObjC class for a name."
  [name]
  (or (get @class-cache name)
      (let [c (objc-get-class name)]
        (swap! class-cache assoc name c)
        c)))

;; --- strings ----------------------------------------------------------------
(defn nsstring
  "Create an NSString (autoreleased) from a jolt string."
  [s]
  (objc-msg-send-1s (cls "NSString") (sel "stringWithUTF8String:") s))

(defn nsstring->str
  "Read an NSString as a jolt string, or nil when the pointer is null."
  [s]
  (when-not (ffi/null? s)
    (objc-msg-send-0cstr s (sel "UTF8String"))))

(defn number-int
  "An NSNumber wrapping an integer (for attributed-string attribute values)."
  [n]
  (objc-msg-send-1i64 (cls "NSNumber") (sel "numberWithInt:") n))

;; --- NSApplication ----------------------------------------------------------
(defn shared-application [] (objc-msg-send-0 (cls "NSApplication") (sel "sharedApplication")))
(defn set-activation-policy! [app v] (objc-msg-send-1i64void app (sel "setActivationPolicy:") v))
(defn run-app! [app] (objc-msg-send-run app (sel "run")))

;; --- uncaught Objective-C exceptions -----------------------------------------
;; An NSException thrown inside an AppKit call (a constraint set up wrong, a
;; selector a view does not answer) unwinds to objc_terminate and the process
;; aborts: a native crash report, no jolt backtrace, nothing to catch. It
;; cannot be turned into a jolt throwable from here -- the ObjC unwinder cannot
;; cross the Chez frames between the throw and the foreign call, so nothing
;; short of a C @try around objc_msgSend would let the call return -- but the
;; runtime's uncaught-exception handler runs BEFORE the abort, and there the
;; exception's name, reason and the jolt frames that made the call can still be
;; written. So the report a user sees names the call, not just SIGABRT.
(ffi/defcfn ns-set-uncaught-exception-handler "NSSetUncaughtExceptionHandler" [:pointer] :void)
(defonce ^:private uncaught-report
  (ffi/foreign-callable
    (fn [exc]
      (let [nm (try (nsstring->str (objc-msg-send-0 exc (sel "name"))) (catch :default _ "NSException"))
            why (try (nsstring->str (objc-msg-send-0 exc (sel "reason"))) (catch :default _ ""))]
        (binding [*out* *err*]
          (println (str "glimmer-appkit: uncaught " nm ": " why))
          (println "  the process aborts now; the Objective-C exception cannot be caught from jolt.")
          (when-let [dump (resolve 'jolt.host/backtrace-string)]
            (when-let [bt (try (dump) (catch :default _ nil))]
              (println "  jolt trace:") (println bt)))
          (flush))))
    [:pointer] :void :collect-safe))
(defn install-uncaught-exception-report!
  "Have an uncaught NSException print its name, reason and the jolt trace
  before the abort. Called by run!; harmless to call twice."
  []
  (ns-set-uncaught-exception-handler uncaught-report))
(defn stop-app! [app] (objc-msg-send-1pvoid app (sel "stop:") ffi/null))
(defn terminate-app! [app] (objc-msg-send-1pvoid app (sel "terminate:") ffi/null))
(defn activate! [app] (objc-msg-send-1intvoid app (sel "activateIgnoringOtherApps:") 1))
(defn set-app-delegate! [app d] (objc-msg-send-1pvoid app (sel "setDelegate:") d))
(defn post-event-at-start! [app e] (objc-msg-send-1pcharvoid app (sel "postEvent:atStart:") e 1))
(defn application-defined-event []
  (objc-msg-send-other-event
    (cls "NSEvent") (sel "otherEventWithType:location:modifierFlags:timestamp:windowNumber:context:subtype:data1:data2:")
    EVENT-APPLICATION-DEFINED 0.0 0.0 0 0.0 0 ffi/null 0 0 0))

;; --- NSWindow ---------------------------------------------------------------
(defn window-title! [w t] (objc-msg-send-1pvoid w (sel "setTitle:") (nsstring t)))
(defn window-new
  "Create an NSWindow with the given content size and title."
  [title width height]
  (let [w (objc-msg-send-4d3
            (objc-msg-send-0 (cls "NSWindow") (sel "alloc"))
            (sel "initWithContentRect:styleMask:backing:defer:")
            0.0 0.0 (double width) (double height) WINDOW-STYLE NS-BACKING-BUFFERED 0)]
    (objc-msg-send-1intvoid w (sel "setReleasedWhenClosed:") 0)
    (when title (window-title! w title))
    w))
(defn window-content [w] (objc-msg-send-0 w (sel "contentView")))
(defn window-center! [w] (objc-msg-send-0void w (sel "center")))
(defn window-show! [w] (objc-msg-send-1pvoid w (sel "makeKeyAndOrderFront:") ffi/null))
(defn window-hide! [w] (objc-msg-send-1pvoid w (sel "orderOut:") ffi/null))

;; --- NSView layout ----------------------------------------------------------
(defn add-subview! [parent child] (objc-msg-send-1pvoid parent (sel "addSubview:") child))
(defn remove-from-superview! [v] (objc-msg-send-0void v (sel "removeFromSuperview")))
(defn set-translates-autoresizing! [v b]
  (objc-msg-send-1intvoid v (sel "setTranslatesAutoresizingMaskIntoConstraints:") (if b 1 0)))
(defn set-tooltip! [v s] (objc-msg-send-1pvoid v (sel "setToolTip:") (nsstring s)))
(defn set-hidden! [v b] (objc-msg-send-1intvoid v (sel "setHidden:") (if b 1 0)))
(defn set-hugging! [v priority orientation]
  ;; NSLayoutPriority is a FLOAT (32-bit), not CGFloat — a double's low bits
  ;; read as ~0.0f and AppKit rejects it.
  (objc-msg-send-1f1i64void v (sel "setContentHuggingPriority:forOrientation:") (float priority) orientation))
(defn constraint [view1 attr1 view2 attr2 multiplier constant]
  (objc-msg-send-constraint
    (cls "NSLayoutConstraint")
    (sel "constraintWithItem:attribute:relatedBy:toItem:attribute:multiplier:constant:")
    view1 attr1 RELATION-EQUAL view2 attr2 (double multiplier) (double constant)))
(defn- pin-constraints! [child parent priority]
  (doseq [[a1 a2] [[ATTR-LEADING ATTR-LEADING] [ATTR-TRAILING ATTR-TRAILING]
                   [ATTR-TOP ATTR-TOP] [ATTR-BOTTOM ATTR-BOTTOM]]]
    (let [c (constraint child a1 parent a2 1.0 0.0)]
      (when (< priority PRIORITY-REQUIRED)
        (objc-msg-send-1fvoid c (sel "setPriority:") (float priority)))
      (objc-msg-send-1pvoid parent (sel "addConstraint:") c))))
(defn pin!
  "Pin `child` to fill `parent` via autolayout (required priority)."
  [child parent]
  (set-translates-autoresizing! child false)
  (pin-constraints! child parent PRIORITY-REQUIRED))
(defn pin-low!
  "Pin `child` to `parent` at low priority — the child's intrinsic size wins
  when larger, so it can scroll. For a scroll view's document view."
  [child parent]
  (set-translates-autoresizing! child false)
  (pin-constraints! child parent PRIORITY-LOW))

;; --- NSStackView ------------------------------------------------------------
(defn stack-new []
  (objc-msg-send-1p (cls "NSStackView") (sel "stackViewWithViews:")
                    (objc-msg-send-0 (cls "NSArray") (sel "array"))))
(defn stack-orientation [s] (objc-msg-send-0i64 s (sel "orientation")))
(defn stack-orientation! [s o] (objc-msg-send-1i64void s (sel "setOrientation:") o))
(defn stack-spacing! [s d] (objc-msg-send-1dvoid s (sel "setSpacing:") (double d)))
(defn stack-alignment! [s a] (objc-msg-send-1i64void s (sel "setAlignment:") a))
(defn stack-distribution! [s d] (objc-msg-send-1i64void s (sel "setDistribution:") d))
(defn stack-edge-insets! [s top left bottom right]
  (objc-msg-send-4dvoid s (sel "setEdgeInsets:")
                        (double (or top 0)) (double (or left 0))
                        (double (or bottom 0)) (double (or right 0))))
(defn stack-add-arranged! [s v] (objc-msg-send-1pvoid s (sel "addArrangedSubview:") v))
(defn stack-remove-arranged! [s v] (objc-msg-send-1pvoid s (sel "removeArrangedSubview:") v))
(defn stack-insert-arranged! [s v i] (objc-msg-send-1p1i64void s (sel "insertArrangedSubview:atIndex:") v i))
(defn stack-index-of!
  "The arranged index of `v` (NSStackView has no indexOfArrangedSubview:; ask its
  arrangedSubviews array, which does)."
  [s v]
  (objc-msg-send-1p-i64ret (objc-msg-send-0 s (sel "arrangedSubviews")) (sel "indexOfObject:") v))
(defn stack-arranged! [s] (objc-msg-send-0 s (sel "arrangedSubviews")))

;; --- NSArray ----------------------------------------------------------------
(defn array-count [a] (objc-msg-send-0i64 a (sel "count")))
(defn array-get [a i] (objc-msg-send-1i64 a (sel "objectAtIndex:") i))

;; --- controls ---------------------------------------------------------------
(defn button-new
  "A push button with an optional title (target/action wired later)."
  [title]
  (objc-msg-send-3p (cls "NSButton") (sel "buttonWithTitle:target:action:")
                    (nsstring title) ffi/null ffi/null))
(defn checkbox-new
  "A switch-style checkbox (the AppKit analogue of GTK's checkbutton)."
  [title]
  (objc-msg-send-3p (cls "NSButton") (sel "checkboxWithTitle:target:action:")
                    (nsstring title) ffi/null ffi/null))
(defn label-new
  "A non-editable, non-bordered label."
  [s]
  (objc-msg-send-1p (cls "NSTextField") (sel "labelWithString:") (nsstring s)))
(defn entry-new
  "An editable, bordered text field."
  []
  (let [e (objc-msg-send-4d (objc-msg-send-0 (cls "NSTextField") (sel "alloc"))
                            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void e (sel "setBezelStyle:") BEZEL-ROUNDED)
    (objc-msg-send-1intvoid e (sel "setEditable:") 1)
    (objc-msg-send-1intvoid e (sel "setSelectable:") 1)
    (objc-msg-send-1intvoid e (sel "setBordered:") 1)
    (objc-msg-send-1intvoid e (sel "setDrawsBackground:") 1)
    e))
(defn control-title! [c s] (objc-msg-send-1pvoid c (sel "setTitle:") (nsstring s)))
(defn control-string! [c s] (objc-msg-send-1pvoid c (sel "setStringValue:") (nsstring s)))
(defn control-string [c] (nsstring->str (objc-msg-send-0 c (sel "stringValue"))))
(defn control-placeholder! [c s] (objc-msg-send-1pvoid c (sel "setPlaceholderString:") (nsstring s)))
(defn control-enabled! [c b] (objc-msg-send-1intvoid c (sel "setEnabled:") (if b 1 0)))
(defn control-target! [c t] (objc-msg-send-1pvoid c (sel "setTarget:") t))
(defn control-action! [c s] (objc-msg-send-1pvoid c (sel "setAction:") s))
(defn control-delegate! [c d] (objc-msg-send-1pvoid c (sel "setDelegate:") d))
(defn control-state! [c v] (objc-msg-send-1i64void c (sel "setState:") v))
(defn control-align! [c a] (objc-msg-send-1i64void c (sel "setAlignment:") a))
(defn control-line-break! [c m] (objc-msg-send-1i64void c (sel "setLineBreakMode:") m))
(defn control-max-lines! [c n] (objc-msg-send-1i64void c (sel "setMaximumNumberOfLines:") n))
(defn control-preferred-width! [c d] (objc-msg-send-1dvoid c (sel "setPreferredMaxLayoutWidth:") (double d)))
(defn control-attributed! [c a] (objc-msg-send-1pvoid c (sel "setAttributedStringValue:") a))
(defn control-font! [c f] (objc-msg-send-1pvoid c (sel "setFont:") f))

;; --- NSBox / NSScrollView ---------------------------------------------------
(defn box-new
  "A titled NSBox — the AppKit analogue of GTK's frame."
  []
  (let [b (objc-msg-send-4d
            (objc-msg-send-0 (cls "NSBox") (sel "alloc"))
            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void b (sel "setTitlePosition:") TITLE-POSITION-AT-TOP)
    (objc-msg-send-2dvoid b (sel "setContentViewMargins:") 0.0 0.0)
    b))
(defn box-title! [b s] (objc-msg-send-1pvoid b (sel "setTitle:") (nsstring s)))
(defn box-content [b] (objc-msg-send-0 b (sel "contentView")))
(defn separator-new
  "An NSBox separator — a horizontal line. (AppKit has no vertical separator
  primitive; a :vertical :separator renders as nothing in v1.)"
  []
  (let [b (objc-msg-send-4d
            (objc-msg-send-0 (cls "NSBox") (sel "alloc"))
            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1i64void b (sel "setBoxType:") BOX-SEPARATOR)
    b))
(defn scroll-new []
  (let [s (objc-msg-send-4d (objc-msg-send-0 (cls "NSScrollView") (sel "alloc"))
                            (sel "initWithFrame:") 0.0 0.0 0.0 0.0)]
    (objc-msg-send-1intvoid s (sel "setHasVerticalScroller:") 1)
    (objc-msg-send-1intvoid s (sel "setHasHorizontalScroller:") 0)
    (objc-msg-send-1intvoid s (sel "setAutohidesScrollers:") 1)
    s))
(defn scroll-document! [s v] (objc-msg-send-1pvoid s (sel "setDocumentView:") v))
(defn scroll-clip [s] (objc-msg-send-0 s (sel "contentView")))
(defn scroll-top! [s]
  (let [clip (scroll-clip s)]
    (when-not (ffi/null? clip)
      (objc-msg-send-2dvoid clip (sel "scrollToPoint:") 0.0 0.0))))

;; --- fonts / colors / attributed strings ------------------------------------
(defn system-font-size [size] (objc-msg-send-1d (cls "NSFont") (sel "systemFontOfSize:") (double size)))
(defn bold-font-size [size] (objc-msg-send-1d (cls "NSFont") (sel "boldSystemFontOfSize:") (double size)))
(defn italic-font-size [size] (objc-msg-send-1d (cls "NSFont") (sel "italicSystemFontOfSize:") (double size)))
(defn- hex-digit [c]
  (let [n (int c)]
    (cond (<= 48 n 57) (- n 48)
          (<= 97 n 102) (- n 87)
          (<= 65 n 70) (- n 55)
          :else (throw (ex-info (str "glimmer-appkit: bad hex digit " c) {})))))
(defn- hex->int [s] (reduce (fn [acc c] (+ (* acc 16) (hex-digit c))) 0 s))
(defn color-hex
  "Parse \"#rrggbb\" (or \"#rgb\") into an NSColor."
  [hex]
  (let [h (subs hex 1)
        h (if (= 3 (count h)) (apply str (mapcat (fn [c] [c c]) h)) h)]
    (objc-msg-send-4d (cls "NSColor") (sel "colorWithSRGBRed:green:blue:alpha:")
                      (/ (hex->int (subs h 0 2)) 255.0)
                      (/ (hex->int (subs h 2 4)) 255.0)
                      (/ (hex->int (subs h 4 6)) 255.0)
                      1.0)))
(defn attributed-new
  "An NSMutableAttributedString over a plain string."
  [s]
  (objc-msg-send-1p (objc-msg-send-0 (cls "NSMutableAttributedString") (sel "alloc"))
                    (sel "initWithString:") (nsstring s)))
(defn attributed-add!
  "Add an attribute over [start, start+len)."
  [a key value start len]
  (objc-msg-send-2p2i64void a (sel "addAttribute:value:range:") (nsstring key) value start len))
(defn attributed-length [a] (objc-msg-send-0i64 a (sel "length")))

;; --- NSTimer / NSRunLoop ----------------------------------------------------
(defn timer-after!
  "Schedule a one-shot timer on the current thread's run loop; `target`/`selector`
  fire on the main loop while NSApplication runs."
  [ms target selector]
  (objc-msg-send-1d3pchar
    (cls "NSTimer")
    (sel "scheduledTimerWithTimeInterval:target:selector:userInfo:repeats:")
    (/ (double ms) 1000.0) target selector ffi/null 0))
(defn current-run-loop [] (objc-msg-send-0 (cls "NSRunLoop") (sel "currentRunLoop")))
(defn run-loop-until! [rl date] (objc-msg-send-1p rl (sel "runUntilDate:") date))
(defn date-in [secs] (objc-msg-send-1d (cls "NSDate") (sel "dateWithTimeIntervalSinceNow:") (double secs)))
