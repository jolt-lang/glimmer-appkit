(ns glimmer-appkit.widget
  "Hiccup -> AppKit views. A data-driven registry maps hiccup tags to view
  constructors, prop maps to AppKit setters, and :on-* event keys to handlers
  wired through the shared action/delegate target (a dynamic ObjC class whose
  method IMPs are jolt foreign-callables). This layer creates/patches views and
  manages container children; glimmer's reconciler decides when, reaching these
  functions through the backend map in glimmer-appkit.core.

  Tag mapping (GTK widget -> AppKit view):
    :window    NSWindow                (single child, pinned to the content view)
    :box/:hbox/:vbox  NSStackView      (orientation = NSUserInterfaceLayoutOrientation)
    :button    NSButton (push)
    :label     NSTextField (label style)
    :entry     NSTextField (editable, bordered)
    :checkbutton NSButton (switch style)
    :separator NSBox separator (horizontal only in v1)
    :frame     NSBox (titled)
    :scrolled  NSScrollView (single document view)

  Events are connected once at mount. AppKit does NOT fire action/delegate
  callbacks for programmatic setState:/setStringValue: (unlike GTK's
  set_active/set_text), so the re-render feedback suppression the GTK backend
  needs is unnecessary here.

  v1 layout notes: box :margin maps to the stack's edge insets; per-child
  :halign/:valign drive the PARENT stack's alignment (last child with an
  alignment wins), which matches every bundled example; :hexpand/:vexpand lower
  the child's content-hugging priority so it stretches along the stacking axis."
  (:require [clojure.string :as str]
            [glimmer-appkit.ffi :as u]
            [hiccup2.core :as hiccup]
            [jolt.ffi :as ffi]))

;; --- value marshalling -------------------------------------------------------
(defn- ->bool [x] (if x 1 0))

(defn escape-markup
  "Escape `&`, `<`, `>` so `s` can be embedded safely inside a Pango markup
  string passed to a label's :markup prop. `&` is escaped first so the
  angle-bracket escapes are not themselves re-encoded."
  ^String [^String s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

;; --- Pango markup from hiccup data ------------------------------------------
;; Pango's text-attribute markup is a small XML subset (b, i, span, a, ...), NOT
;; HTML. Hiccup serializes vectors to a string and escapes content/attrs, but it
;; is HTML-flavoured — it will happily emit <div>, <br>, or a typo'd span
;; attribute. So we validate the hiccup *data* against Pango's vocabulary before
;; handing it to hiccup for serialization: a bad fragment fails loudly at the
;; call site instead of rendering silently wrong. Attribute names mirror Pango's
;; own (underscores: :font_family, :letter_spacing).
(def ^:private pango-tags
  "Pango markup vocabulary: tag -> the set of attributes it accepts, or nil when
  the tag takes no attributes."
  {:span #{:font_desc :font_family :face :size :style :weight :variant :stretch
           :foreground :color :background :alpha :underline :underline_color :rise
           :strikethrough :strikethrough_color :fallback :lang :letter_spacing
           :show :line_height :allow_breaks :insert_hyphens :text_transform
           :gravity :gravity_hint :overline :overline_color}
   :a    #{:href}
   :b nil :big nil :i nil :mark nil :s nil :small nil :sub nil :sup nil :tt nil
   :u nil})

(defn- markup-element? [form] (and (vector? form) (keyword? (first form))))

(declare markup-validate!)

(defn- markup-validate-element! [form]
  (let [tag     (first form)
        body    (rest form)
        attrs?  (map? (first body))
        attrs   (if attrs? (first body) nil)
        children (if attrs? (rest body) body)]
    (if-not (contains? pango-tags tag)
      (throw (ex-info (str "glimmer/markup: :" (name tag) " is not a Pango tag")
                      {:tag tag})))
    (let [allowed (get pango-tags tag)]
      (when (and attrs (seq attrs))
        (if (nil? allowed)
          (throw (ex-info (str "glimmer/markup: :" (name tag) " takes no attributes")
                          {:tag tag :attrs (keys attrs)}))
          (doseq [k (keys attrs)]
            (when-not (contains? allowed k)
              (throw (ex-info (str "glimmer/markup: :" (name k)
                                   " is not a :" (name tag) " attribute")
                              {:tag tag :attr k}))))))
      (run! markup-validate! children))))

(defn- markup-validate! [form]
  (cond
    (markup-element? form)  (markup-validate-element! form)
    (sequential? form)      (run! markup-validate! form)
    :else                   nil))

(defn markup
  "Render hiccup `form` to a Pango markup string for a label's :markup prop.

  [:span {:foreground \"#8e939d\"} \"Nothing to do yet\"]
  [:b [:i \"bold italic\"]]

  Serialization (escaping, seq expansion) is delegated to hiccup; the data is
  first validated against Pango's tag/attribute vocabulary, so an HTML-only tag
  (:div, :br) or a typo'd attribute (:forground) throws here rather than
  producing markup a label can't render. Pango attribute names use underscores
  (:font_family, :letter_spacing) to match Pango's own spelling."
  [form]
  (markup-validate! form)
  (str (hiccup/html form)))

(defn markup-string
  "Coerce a label's :markup prop to a Pango markup string. A string passes
  through as-is (already markup); anything else is treated as hiccup and
  rendered via `markup`."
  [m]
  (if (string? m) m (markup m)))

;; --- Pango markup -> NSAttributedString -------------------------------------
;; AppKit has no Pango. :markup renders through a small subset mapped onto
;; NSAttributedString attributes: <b> <i> <s> <u> and span{foreground|color,
;; size (named, or an integer in 1/1024ths of a point), weight=bold,
;; strikethrough, underline}. Unknown-but-valid tags/attrs (validated above
;; against the full Pango vocabulary) are parsed and dropped — their text
;; survives, which keeps a Pango fragment from silently vanishing.
(def ^:private named-sizes
  {"xx-small" 9 "x-small" 11 "small" 12 "medium" 13
   "large" 15 "x-large" 17 "xx-large" 22})

(defn- parse-attrs
  "Extract k='v' pairs from a tag string."
  [tag]
  (into {}
        (for [[_ k v] (re-seq #"([a-zA-Z_]+)=['\"]([^'\"]*)['\"]" tag)]
          [(keyword k) v])))

(defn- tag-name [tag] (str/replace (str/replace tag #"[<>/]" "") #"\s.*$" ""))

(defn- open-tag [stack tag]
  (case (tag-name tag)
    "b"    (conj stack {:bold true})
    "i"    (conj stack {:italic true})
    "s"    (conj stack {:strike true})
    "u"    (conj stack {:underline true})
    "span" (let [a (parse-attrs tag)
                 style (cond-> {}
                         (or (:foreground a) (:color a))
                         (assoc :color (or (:foreground a) (:color a)))
                         (:size a) (assoc :size (:size a))
                         (= "bold" (:weight a)) (assoc :weight true)
                         (= "true" (:strikethrough a)) (assoc :strike true)
                         (= "true" (:underline a)) (assoc :underline true))]
             (conj stack style))
    stack))

(defn- markup->segments
  "Parse a Pango markup string into [[text style-map] ...] segments."
  [^String s]
  (first
    (reduce
      (fn [[segs stack] [_full tag txt]]
        (cond
          tag (if (str/starts-with? tag "</")
                [segs (pop stack)]
                [segs (open-tag stack tag)])
          :else [ (conj segs [txt (apply merge stack)]) stack]))
      [[] []]
      (re-seq #"(</?[a-zA-Z]+(?:\s+[^<>]*)?>)|([^<>]+)" s))))

(defn- decode-entities [s]
  (-> s
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&apos;" "'")
      (str/replace "&amp;" "&")))

(defn- pango-size->pt
  "Pango font sizes are a named size (\"large\") or an integer in 1/1024ths of
  a point (\"30000\" = 29.3pt). Map either to a point size."
  [s]
  (or (named-sizes s)
      (when (and (string? s) (re-matches #"\d+" s))
        (/ (reduce (fn [acc c] (+ (* acc 10) (- (int c) 48))) 0 s) 1024.0))))

(defn- style-font [style]
  (let [size (double (or (pango-size->pt (:size style)) 13.0))]
    (cond
      (:weight style) (u/bold-font-size size)
      (:italic style) (u/italic-font-size size)
      :else           (u/system-font-size size))))

(defn- apply-style! [a style start len]
  (when (pos? len)
    (let [font (style-font style)]
      (when font (u/attributed-add! a u/NS-FONT-ATTR font start len)))
    (when (:strike style)
      (u/attributed-add! a u/NS-STRIKETHROUGH-ATTR (u/number-int 1) start len))
    (when (:underline style)
      (u/attributed-add! a u/NS-UNDERLINE-ATTR (u/number-int 1) start len))
    (when-let [c (:color style)]
      (u/attributed-add! a u/NS-FOREGROUND-COLOR-ATTR (u/color-hex c) start len))))

(defn markup->attributed
  "Render a Pango markup STRING to an NSAttributedString (AppKit label content)."
  [^String s]
  (let [segs (map (fn [[t st]] [(decode-entities t) st]) (markup->segments s))
        a    (u/attributed-new (apply str (map first segs)))]
    (loop [segs (seq segs) pos 0]
      (when-let [[txt style] (first segs)]
        (let [len (count txt)]
          (apply-style! a style pos len)
          (recur (next segs) (+ pos len)))))
    a))

;; --- tag aliases (sugar) -----------------------------------------------------
;; :hbox / :vbox are both NSStackView; the difference is orientation.
;; normalize-tag maps them to the :box spec, and with-orientation injects the
;; matching :orientation so a bare [:hbox ...] lays out horizontally.
(def ^:private aliases {:hbox :box :vbox :box})
(def ^:private tag-orientation {:hbox :horizontal :vbox :vertical})

(defn- normalize-tag [tag] (get aliases tag tag))

(defn with-orientation
  "Inject the orientation implied by an :hbox/:vbox tag into its props, unless
  the caller already set :orientation. A no-op for any other tag."
  [tag props]
  (if-let [o (tag-orientation tag)]
    (if (contains? props :orientation) props (assoc props :orientation o))
    props))

;; --- event registry ----------------------------------------------------------
;; The :on-* keys glimmer knows. AppKit wiring differs from GTK signals: action
;; events (:on-click/:on-toggled/:on-activate) route through setTarget:/
;; setAction: with a shared selector, :on-change through the text-field delegate.
;; The atom keeps the API shape (register-signal! for extensions) and lets
;; apply-props! skip event keys.
(def signals
  (atom {:on-click    true
         :on-change   true
         :on-activate true
         :on-toggled  true}))

(defn register-signal!
  "Register an :on-* event key so apply-props! skips it (kept for API
  compatibility with the GTK backend's extension surface)."
  [event _gtk-signal]
  (swap! signals assoc event true)
  nil)

;; --- the shared action/delegate target ---------------------------------------
;; One dynamic ObjC class ("GlimmerTarget") carries every event. Its method IMPs
;; are jolt foreign-callables that dispatch on the sender pointer, so a single
;; instance serves as every control's target and every text field's delegate,
;; and as the app delegate (applicationShouldTerminateAfterLastWindowClosed:).
(defonce ^:private actions (atom {}))      ; control -> zero-arg handler
(defonce ^:private changes (atom {}))      ; control -> (fn [text])
(defonce ^:private auto-quit-app (atom nil))

(defonce ^:private fire-cb
  (ffi/foreign-callable
    (fn [_self _cmd sender]
      (when-let [h (get @actions sender)] (h))
      0)
    [:pointer :pointer :pointer] :void :collect-safe))

(defonce ^:private change-cb
  (ffi/foreign-callable
    (fn [_self _cmd notif]
      (let [control (u/objc-msg-send-0 notif (u/sel "object"))]
        (when-let [h (get @changes control)]
          (h (u/control-string control))))
      0)
    [:pointer :pointer :pointer] :void :collect-safe))

(defonce ^:private quit-cb
  (ffi/foreign-callable
    (fn [_self _cmd _timer]
      (when-let [app @auto-quit-app]
        (u/stop-app! app)
        (u/post-event-at-start! app (u/application-defined-event)))
      0)
    [:pointer :pointer :pointer] :void :collect-safe))

(defonce ^:private terminate-cb
  (ffi/foreign-callable (fn [_ _ _] 1) [:pointer :pointer :pointer] :char :collect-safe))

(defonce invoker
  (let [existing (u/objc-get-class "GlimmerTarget")]
    (if (and existing (not (ffi/null? existing)))
      (u/objc-msg-send-0 existing (u/sel "new"))
      (let [c (u/objc-allocate-class-pair (u/cls "NSObject") "GlimmerTarget" 0)]
        (u/class-add-method c (u/sel "fire:") fire-cb "v@:@")
        (u/class-add-method c (u/sel "controlTextDidChange:") change-cb "v@:@")
        (u/class-add-method c (u/sel "autoQuit:") quit-cb "v@:@")
        (u/class-add-method c (u/sel "applicationShouldTerminateAfterLastWindowClosed:") terminate-cb "c@:@")
        (u/objc-register-class-pair c)
        (u/objc-msg-send-0 c (u/sel "new"))))))

(defn auto-quit!
  "Schedule the app to quit after `ms` (the :auto-quit-ms run option)."
  [app ms]
  (reset! auto-quit-app app)
  (u/timer-after! ms invoker (u/sel "autoQuit:")))

(defn connect-signals!
  "Wire every :on-* key in `props` on `widget`. Connected once at mount; a
  programmatic prop change on re-render never fires these (AppKit actions and
  delegate callbacks are user-interaction-only), so nothing needs suppressing."
  [widget props]
  (when (or (:on-click props) (:on-toggled props) (:on-activate props))
    (u/control-target! widget invoker)
    (u/control-action! widget (u/sel "fire:")))
  (when-let [h (:on-change props)]
    (swap! changes assoc widget h)
    (u/control-delegate! widget invoker))
  (when-let [h (:on-click props)]    (swap! actions assoc widget h))
  (when-let [h (:on-toggled props)]  (swap! actions assoc widget h))
  (when-let [h (:on-activate props)] (swap! actions assoc widget h)))

;; --- widget specs ------------------------------------------------------------
;; Each spec: {:ctor (fn [props] view) :apply (fn [view props]) :container kw}
(defn- window-spec []
  {:ctor    (fn [p] (u/window-new (:title p) (or (:width p) 400) (or (:height p) 300)))
   :apply   (fn [w p]
              (when (:title p) (u/window-title! w (:title p)))
              (when (false? (:visible p)) (u/window-hide! w)))
   :container :window})

(defn- box-margins
  "The stack's edge insets implied by a box's margin props, or nil."
  [p]
  (let [m (:margin p)
        top (or (:margin-top p) m) bottom (or (:margin-bottom p) m)
        left (or (:margin-left p) (:margin-start p) m)
        right (or (:margin-right p) (:margin-end p) m)]
    (when (or top left bottom right) [top left bottom right])))

(defn- box-spec []
  {:ctor  (fn [_] (u/stack-new))
   :apply (fn [w p]
            (when (contains? p :spacing)     (u/stack-spacing! w (:spacing p)))
            (when (contains? p :homogeneous)
              (u/stack-distribution! w (if (:homogeneous p)
                                         u/DISTRIBUTION-FILL-EQUALLY
                                         u/DISTRIBUTION-GRAVITY)))
            (when (contains? p :orientation)
              (u/stack-orientation! w (if (= :vertical (:orientation p))
                                        u/ORIENTATION-VERTICAL
                                        u/ORIENTATION-HORIZONTAL)))
            (when-let [[t l b r] (box-margins p)]
              (u/stack-edge-insets! w t l b r)))
   :container :box})

(defn- button-spec []
  {:ctor  (fn [p] (u/button-new (or (:label p) "")))
   :apply (fn [w p]
            (when (contains? p :label)     (u/control-title! w (:label p)))
            (when (:tooltip p)             (u/set-tooltip! w (:tooltip p)))
            (when (contains? p :sensitive) (u/control-enabled! w (:sensitive p))))
   :container :none})

(defn- ->text-align [x]
  (cond
    (<= x 0.34) u/TEXT-ALIGN-LEFT
    (>= x 0.66) u/TEXT-ALIGN-RIGHT
    :else       u/TEXT-ALIGN-CENTER))

(defn- ->line-break [e]
  (case e
    :start  u/LINE-BREAK-HEAD
    :middle u/LINE-BREAK-MIDDLE
    :end    u/LINE-BREAK-TAIL
    :none   nil))

(defn- label-spec []
  {:ctor  (fn [p] (u/label-new (or (:label p) (:text p) "")))
   :apply (fn [w p]
            (when (contains? p :label)  (u/control-string! w (:label p)))
            (when (contains? p :text)   (u/control-string! w (:text p)))
            (when (contains? p :markup) (u/control-attributed! w (markup->attributed (markup-string (:markup p)))))
            (when (contains? p :xalign) (u/control-align! w (->text-align (:xalign p))))
            (when (contains? p :wrap)
              (u/control-line-break! w u/LINE-BREAK-WRAP)
              (u/control-max-lines! w 0))
            (when (contains? p :lines)  (u/control-max-lines! w (:lines p)))
            (when-let [m (and (contains? p :ellipsize) (->line-break (:ellipsize p)))]
              (u/control-line-break! w m))
            (when-let [n (or (:max-width-chars p) (:width-chars p))]
              (u/control-preferred-width! w (* 8.0 (double n)))))
   :container :none})

(defn- entry-spec []
  {:ctor  (fn [_] (u/entry-new))
   :apply (fn [w p]
            (when (contains? p :text)        (u/control-string! w (:text p)))
            (when (contains? p :placeholder) (u/control-placeholder! w (:placeholder p)))
            (when (contains? p :sensitive)   (u/control-enabled! w (:sensitive p)))
            (when (:tooltip p)               (u/set-tooltip! w (:tooltip p))))
   :container :none})

(defn- checkbutton-spec []
  {:ctor  (fn [p] (u/checkbox-new (or (:label p) "")))
   :apply (fn [w p]
            (when (contains? p :label)  (u/control-title! w (:label p)))
            (when (contains? p :active) (u/control-state! w (if (:active p) u/STATE-ON u/STATE-OFF)))
            (when (:tooltip p)          (u/set-tooltip! w (:tooltip p))))
   :container :none})

(defn- separator-spec []
  {:ctor  (fn [_] (u/separator-new))
   :apply (fn [_ _] nil)
   :container :none})

(defn- frame-spec []
  {:ctor     (fn [_] (u/box-new))
   :apply    (fn [w p] (when (contains? p :label) (u/box-title! w (or (:label p) ""))))
   :container :frame})

(defn- scrolled-spec []
  ;; A single-document viewport. The document is pinned to the clip view at LOW
  ;; priority so it scrolls inside the allotted area instead of forcing the
  ;; window bigger (mirrors GTK's propagate-natural-size off).
  {:ctor     (fn [_] (u/scroll-new))
   :apply    (fn [w p] (when (contains? p :scroll-top) (u/scroll-top! w)))
   :container :scrolled})

(def specs
  (atom {:window      (window-spec)
         :box         (box-spec)
         :button      (button-spec)
         :label       (label-spec)
         :entry       (entry-spec)
         :checkbutton (checkbutton-spec)
         :separator   (separator-spec)
         :frame       (frame-spec)
         :scrolled    (scrolled-spec)}))

(defn register-widget!
  "Register a widget spec under hiccup `tag`. A spec is
  {:ctor (fn [props] view) :apply (fn [view props]) :container kw
   :connect (fn [view props])?}. :container is :none for a leaf, or :box /
  :window / :frame / :scrolled to reuse an existing child-management strategy."
  [tag spec] (swap! specs assoc tag spec) nil)

(defn- spec-for [tag] (@specs (normalize-tag tag)))

(defn container-kind
  "How a tag holds children: :box (ordered append/remove), :window / :frame /
  :scrolled (single child), or :none (leaf)."
  [tag] (:container (spec-for tag)))

;; --- universal props (apply to every widget, every tag) ----------------------
;; :hexpand/:vexpand lower the child's content-hugging priority so an NSStackView
;; (gravity-area distribution) stretches it along the stacking axis. :halign/
;; :valign are recorded so the PARENT stack's alignment can be derived at append
;; time (NSStackView alignment is a stack-wide property, not per-view).
(def ^:private alignments (atom {}))    ; view -> [halign valign]

(defn- ->stack-alignment [halign valign orientation]
  (if (= orientation u/ORIENTATION-VERTICAL)
    (case halign
      :start u/ATTR-LEADING
      :end   u/ATTR-TRAILING
      u/ATTR-CENTER-X)
    (case valign
      :top    u/ATTR-TOP
      :bottom u/ATTR-BOTTOM
      u/ATTR-CENTER-Y)))

(defn apply-widget-props!
  [widget props]
  (when (contains? props :hexpand)
    (u/set-hugging! widget (if (:hexpand props) u/PRIORITY-VERY-LOW u/PRIORITY-REQUIRED)
                     u/ORIENTATION-HORIZONTAL))
  (when (contains? props :vexpand)
    (u/set-hugging! widget (if (:vexpand props) u/PRIORITY-VERY-LOW u/PRIORITY-REQUIRED)
                     u/ORIENTATION-VERTICAL))
  (when (or (contains? props :halign) (contains? props :valign))
    (swap! alignments assoc widget [(:halign props) (:valign props)])))

;; --- public create / patch ---------------------------------------------------
(defn create!
  "Construct a fresh AppKit view for `tag`, apply `props`, and wire any :on-*
  handlers. Returns the view pointer. Children are NOT added here — the
  reconciler appends them so it can reuse existing children across renders."
  [tag props]
  (let [props (with-orientation tag props)
        s (spec-for tag)
        widget ((:ctor s) props)]
    ((:apply s) widget props)
    (apply-widget-props! widget props)
    (connect-signals! widget props)
    (when-let [connect (:connect s)] (connect widget props))
    widget))

(defn apply-props!
  "Re-apply the prop map to an existing view (re-render path). Skips :on-* keys
  (events stay wired from mount) and keys whose value is nil."
  [tag widget props]
  (let [applied (into {} (filter (fn [[k v]] (and (not (contains? @signals k)) (some? v)))
                                 (with-orientation tag props)))]
    ((:apply (spec-for tag)) widget applied)
    (apply-widget-props! widget applied)))

(defn show!
  "Make a view visible. AppKit views are visible by default; :visible false
  hides instead."
  [widget props]
  (u/set-hidden! widget (false? (:visible props))))

;; --- container child management ----------------------------------------------
(defn- maybe-align!
  "Derive the parent stack's alignment from a child's :halign/:valign."
  [parent child]
  (when-let [[halign valign] (get @alignments child)]
    (u/stack-alignment! parent (->stack-alignment halign valign (u/stack-orientation parent)))))

(defn append-child!
  "Add `child` to the end of `parent`. Dispatches on the parent's container kind."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box      (do (u/stack-add-arranged! parent child) (maybe-align! parent child))
    :window   (let [c (u/window-content parent)] (u/add-subview! c child) (u/pin! child c))
    :frame    (let [c (u/box-content parent)]    (u/add-subview! c child) (u/pin! child c))
    :scrolled (do (u/scroll-document! parent child)
                  (u/pin-low! child (u/scroll-clip parent)))
    nil))

(defn remove-child!
  "Remove `child` from `parent`."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box      (do (u/stack-remove-arranged! parent child)
                  (u/remove-from-superview! child))
    :window   (u/remove-from-superview! child)
    :frame    (u/remove-from-superview! child)
    :scrolled (u/scroll-document! parent ffi/null)
    nil))

(defn replace-child!
  "Replace `old-child` with `new-child` at the same position in `parent`."
  [parent-tag parent old-child new-child]
  (case (container-kind parent-tag)
    :box      (do (remove-child! parent-tag parent old-child)
                  (append-child! parent-tag parent new-child))
    :window   (do (remove-child! parent-tag parent old-child)
                  (append-child! parent-tag parent new-child))
    :frame    (do (remove-child! parent-tag parent old-child)
                  (append-child! parent-tag parent new-child))
    :scrolled (do (remove-child! parent-tag parent old-child)
                  (append-child! parent-tag parent new-child))
    nil))

(defn reorder-child!
  "Move `child` to sit immediately after `sibling` (nil = move to first position)
  within `parent`. Only NSStackView supports positional reordering; the
  single-child containers (window/frame/scrolled) no-op."
  [parent-tag parent child sibling]
  (when (= :box (container-kind parent-tag))
    (u/stack-remove-arranged! parent child)
    (if (nil? sibling)
      (u/stack-insert-arranged! parent child 0)
      (let [i (u/stack-index-of! parent sibling)]
        (u/stack-insert-arranged! parent child (inc i))))))

;; --- reading the live tree (for tests / smoke examples) ----------------------
(defn stack-children
  "The arranged subviews of a stack, in visual order."
  [stack]
  (let [arr (u/stack-arranged! stack)
        n   (u/array-count arr)]
    (mapv (fn [i] (u/array-get arr i)) (range n))))
