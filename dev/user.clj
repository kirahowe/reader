(ns user
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :as state]
            [kira.reader.system :as system]))

(ig-repl/set-prep! #(system/config))

(defn go    [] (ig-repl/go))
(defn halt  [] (ig-repl/halt))
(defn reset [] (ig-repl/reset))

(defn system [] state/system)
