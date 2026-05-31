(ns ^{:clj-kondo/config '{:linters {:unused-referred-var {:level :off}
                                    :unused-namespace    {:level :off}}}}
 dev
  (:require [clojure.java.io :as io]
            [integrant.repl :as igr :refer [go halt reset]]
            [integrant.repl.state :refer [config system]]
            [reader.dev.main :as dev-main]
            [reader.main :as main]))

;; `local.edn` is an optional per-developer override. We resolve it via
;; io/resource so a missing file becomes nil (which load-config treats
;; as `{}`); strings, by contrast, are required and load-config throws
;; if they don't resolve.
(def profiles
  (concat dev-main/profiles [(io/resource "local.edn")]))

(igr/set-prep!
 #(main/prep-config profiles))
