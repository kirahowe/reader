(ns user
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [reader.sys :as sys]))

(ig-repl/set-prep!
 #(-> ["base-system.edn" "env.edn"]
      sys/load-configs
      sys/prep-config))

(defn go    [] (ig-repl/go))
(defn halt  [] (ig-repl/halt))
(defn reset [] (ig-repl/reset))

(defn system [] state/system)
