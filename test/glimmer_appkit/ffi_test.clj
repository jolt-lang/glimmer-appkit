(ns glimmer-appkit.ffi-test
  "The binding namespace must LOAD everywhere: its foreign procedures resolve
  their entries on first use, and nothing may call one at namespace load — a
  CFString made at load was what made `(require 'glimmer-appkit.ffi)` raise on
  Linux, where the frameworks are not there to resolve it. This namespace
  requires it (on CI that is Linux) and touches only what needs no framework."
  (:require [clojure.test :refer [deftest is]]
            [glimmer-appkit.ffi :as ffi]))

(deftest ffi-namespace-loads-without-the-frameworks
  (is (= 15 ffi/WINDOW-STYLE))
  (is (fn? ffi/default-mode) "the run loop's mode string is made on first use, not at load"))
