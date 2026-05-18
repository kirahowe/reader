(ns ^{:clj-kondo/config '{:linters {:unused-referred-var {:level :off}
                                    :unused-namespace    {:level :off}}}}
  dev
  (:require [clojure.java.io :as io]
            [integrant.repl :as igr :refer [go halt reset]]
            [integrant.repl.state :refer [config system]]
            [reader.main :as main]))

(def profiles
  (concat main/core-profiles
          [(io/resource "dev.edn")
           (io/resource "local.edn")]))

(igr/set-prep!
 #(main/prep-config profiles))
