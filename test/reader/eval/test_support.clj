(ns reader.eval.test-support
  "Boots the evals app's Integrant system against an embedded Postgres — the
   reader schema (events + corpus) plus the eval schema (eval_labels) — for the
   evals app's system / handler / labels tests."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [reader.main :as main]))

(def eval-profiles
  [(io/resource "eval-system.edn") "embedded-db.edn" "eval-test.edn"])

(defmacro with-eval-system
  "Brings up the eval system, binds it to `binding`, runs `body`, halts on exit."
  [[binding] & body]
  `(let [system# (-> eval-profiles main/prep-config ig/init)
         ~binding system#]
     (try ~@body (finally (ig/halt! system#)))))
