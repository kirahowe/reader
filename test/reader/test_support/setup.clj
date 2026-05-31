(ns reader.test-support.setup
  "Test fixtures. `with-system` brings up the standard test Integrant
   system from `base-system.edn` + `test.edn` — embedded Postgres,
   pooled datasource, migrator, plus the rest of the app — binds the
   running system to `binding`, and halts it on body exit."
  (:require [integrant.core :as ig]
            [reader.main :as main]))

(def test-profiles
  (concat main/core-profiles ["embedded-db.edn" "test.edn"]))

(defmacro with-system
  "Initializes the test Integrant system, binds it to `binding`, runs
   `body`, and halts on exit (including on exception)."
  [[binding] & body]
  `(let [system# (main/exec-config test-profiles)
         ~binding system#]
     (try ~@body (finally (ig/halt! system#)))))
