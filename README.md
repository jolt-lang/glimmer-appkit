# glimmer-appkit

The **AppKit** backend for [glimmer](https://github.com/jolt-lang/glimmer), the
reactive Clojure(-like) UI framework for [jolt](https://github.com/jolt-lang/jolt).
Where glimmer-gtk renders into GTK4 windows on Linux, glimmer-appkit renders the
same hiccup into real macOS windows — `NSWindow`/`NSStackView`/`NSButton`, driven
through the Objective-C runtime by a plain C FFI (no bridging headers, no blocks).

The whole toolkit fits in four namespaces. glimmer owns the reactive core
(ratom, component model, reconciler) and knows nothing about AppKit; this project
supplies the widgets, the prop/event wiring and the app loop, and registers them
through `glimmer.backend`.

```clojure
(ns myapp
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-appkit.core]))            ; installs the AppKit backend

(defn counter []
  (let [count (atom 0)]
    (fn []
      [:vbox {:spacing 12}
       [:label {:label (str "Count: " @count)}]
       [:hbox {:spacing 8}
        [:button {:label "- 1" :on-click #(swap! count dec)}]
        [:button {:label "+ 1" :on-click #(swap! count inc)}]
        [:button {:label "reset" :on-click #(reset! count 0)}]]])))

(defn -main [& _]
  (ui/run counter :title "counter" :width 320 :height 160))
```

Components, reactive state and reconciliation are documented in glimmer's README.
What follows is the AppKit-specific part: what you can put in the hiccup.

## Requirements

macOS, with the Xcode command-line tools installed (for the Objective-C runtime).
AppKit and CoreFoundation are loaded by `glimmer-appkit.ffi` at require time,
guarded to macOS only — nothing is declared under `:jolt/native`, so the headless
test suite (`jolt -M:test`) runs on Linux CI, where no AppKit exists.

## Running

```sh
jolt test               # unit tests (Pango markup, prop normalization — no display needed)
jolt smoke              # reactivity smoke against the live AppKit loop (needs a GUI session)
jolt keyed              # keyed reconciliation smoke against the live widget tree
jolt main-thread-smoke  # regression: app booted on a worker thread hops to the main thread
jolt repl-live-smoke    # regression: a worker-thread ratom write repaints via the main loop
jolt counter            # interactive counter demo (opens a window, blocks)
jolt todo               # interactive todo demo (opens a window, blocks)
```

## Hiccup reference

Elements are `[:tag props? & children]`. `props` is an optional map; children may
be native elements, component invocations (`[my-component arg]`), strings, or
numbers (rendered as labels). `nil` children are skipped.

**Containers:** `:window` (single child), `:box` (`:orientation :horizontal|:vertical`),
`:hbox`, `:vbox`, `:frame` (single child, with an optional `:label`),
`:scrolled` (single child; the child scrolls instead of forcing the window bigger).

**Leaf widgets:** `:button`, `:label`, `:entry`, `:checkbutton`, `:separator`.

**Common props (apply to every widget):**

- `:margin` (all four sides), or `:margin-start`/`:margin-end`/`:margin-top`/`:margin-bottom`
- `:halign`/`:valign` — one of `:fill :start :end :center` (translated to the
  parent stack's alignment, and to the underlying layout attributes where the
  widget sits outside a stack)
- `:hexpand`/`:vexpand` — boolean (lowers the content-hugging priority so the
  stack stretches the widget along the stacking axis)

**Per-tag props:**

- Window: `:title`, `:width`, `:height`, `:visible`
- Box: `:orientation`, `:spacing`, `:homogeneous`
- Button: `:label`, `:tooltip`, `:sensitive`
- Label: `:label`/`:text`, `:markup` (Pango markup), `:xalign` (0.0–1.0),
  `:wrap` (boolean), `:ellipsize` (`:none`/`:start`/`:middle`/`:end`)
- Entry: `:text`, `:placeholder`, `:sensitive`
- Checkbutton: `:label`, `:active`
- Frame: `:label`
- Scrolled: `:scroll-top` — any change to this value scrolls back to the top, for
  a panel whose content is replaced

**Events:**

- `:on-click` — button clicked. Handler takes no args.
- `:on-change` — entry text changed. Handler receives the current text.
- `:on-activate` — entry activated (Return). Handler takes no args.
- `:on-toggled` — checkbutton toggled. Handler takes no args.

Handlers are wired once at mount: click/toggle/activate route through
`setTarget:`/`setAction:` on a single dynamic ObjC class (a jolt `foreign-callable`
IMP), and text changes through the field's delegate. Handlers should close over
reactive cells (not values), so the first render's closure stays correct for the
widget's life. AppKit does not fire actions for programmatic property changes, so
a re-render can't feed back into its own handler.

## Pango markup

A label's `:markup` prop takes a Pango markup string, or hiccup data that is
validated and serialized for you — the same vocabulary glimmer-gtk uses, so a
tree renders identically on both backends:

```clojure
[:label {:markup [:span {:foreground "#8e939d"} "Nothing to do yet"]}]
[:label {:markup [:b [:i "bold italic"]]}]
```

Pango's markup is a small XML subset, not HTML, so the data is checked against
Pango's own vocabulary first: an HTML-only tag (`:div`, `:br`) or a typo'd
attribute throws at the call site instead of rendering silently wrong. Attribute
names use Pango's spelling with underscores (`:font_family`, `:letter_spacing`),
and text is escaped for you. The validated string is parsed back into segments
and applied as an `NSAttributedString`, so `:foreground` colors, `:b`/`:i`/`:s`
styles and `:span` sizes survive.

## Extending the widget set

A consumer can teach glimmer-appkit new hiccup tags at load time:

```clojure
(require '[glimmer-appkit.widget :as w])

(w/register-widget! :my-thing
  {:ctor      (fn [props] (make-the-view props))
   :apply     (fn [widget props] (re-apply props on re-render))
   :container :none})          ; or :box / :window / :frame / :scrolled

(w/register-signal! :on-input "value-changed"
                    (fn [widget] (read-the-value widget)))  ; value-fn optional
```

See `glimmer-appkit.widget` for the worked `:gl-area`-style examples (the registry
is the same open table glimmer-gtk uses).

## Architecture

Three namespaces:

- **`glimmer-appkit.ffi`** — the Objective-C runtime and AppKit, through `jolt.ffi`:
  `objc_getClass`/`sel_registerName`, `objc_msgSend` bound at fixed arities (struct
  args flattened into doubles — a `CGRect` is an HFA of four doubles, so a
  flattened window-init call passes exactly the registers a real method expects;
  never `:varargs`, which shifts FP args onto the stack and corrupts them), plus
  the CFRunLoop pieces the scheduler needs. No logic.
- **`glimmer-appkit.widget`** — hiccup to AppKit: tag to constructor, props to
  setters, `:on-*` to target/action and delegate callbacks (a dynamic `GlimmerTarget`
  ObjC class whose IMPs are jolt `foreign-callable`s), Pango markup to
  `NSAttributedString`, and container child management. The tag and signal
  registries are open (`register-widget!`, `register-signal!`).
- **`glimmer-appkit.core`** — the backend map handed to `glimmer.backend/register!`,
  the `NSApplication` app loop, and the scheduler that marshals off-main-thread
  work (an nREPL eval mutating a ratom) onto the main loop via a `CFRunLoopSource`
  — the AppKit analogue of GTK's `g_idle_add`, without libdispatch or blocks.

## Live development

Under `jolt nrepl-server`, `ui/run` hops the AppKit boot onto the process main
thread (AppKit requires it on macOS) and returns immediately, so the REPL session
stays live. Mutate a `defonce` reactive cell and the window repaints (the write
is marshalled onto the main loop); after redefining components call
`(glimmer.core/reload!)` to re-mount the root in the same window.

## License

MIT (see `LICENSE`).
