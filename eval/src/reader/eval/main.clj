(ns reader.eval.main
  "Entry point for the evals app (ADR 0006). Same Integrant lifecycle as
   reader.main, but the core profile is eval-system.edn (eval routes + handlers +
   operator gate + its own port). Layer an environment profile after it:

     prod:  clojure -M:eval eval-prod.edn
     local: clojure -M:dev:eval embedded-db.edn eval-dev.edn

   (:dev brings the embedded-postgres dep + infra/resources onto the classpath;
   eval-dev.edn supplies the Hanko + operator config.)"
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [reader.main :as main])
  (:gen-class))

(def core-profiles [(io/resource "eval-system.edn")])

(defn profiles-from-args
  "core-profiles plus every CLI arg as an extra profile resource, layered in
   order — so the run command names the environment profile(s) to apply."
  [args]
  (concat core-profiles args))

(defn -main [& args]
  (log/info "evals starting" {:args args})
  (let [system (-> (profiles-from-args args) main/prep-config ig/init)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable #(do (log/info "evals shutdown")
                                              (ig/halt! system))))
    (log/info "evals ready")))
