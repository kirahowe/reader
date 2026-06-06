(ns dev-task
  "Orchestrates `bb dev`. The dev system can live in either of two JVMs:
   one this task starts, or one your editor already jacked in to. Rather
   than pick a lane, `bb dev` cooperates with whatever is already there:

     - no REPL advertised  -> start a fresh dev JVM (`clojure -M:dev`),
       which boots the system, opens a cider-nrepl, and writes
       `.nrepl-port`.
     - a live REPL advertised -> boot the system *inside that JVM* over
       nREPL and exit. The editor owns that JVM's lifecycle, and we
       avoid a second process fighting over the http port and a second
       embedded Postgres.

   The remote path needs the `:dev` alias on the running REPL's
   classpath (integrant.repl, embedded-postgres, the `dev` ns); jacking
   in without it yields an actionable error rather than a stack trace."
  (:require [depends :as dep]
            [repl])
  (:import (java.net ConnectException)))

(def ^:private boot-expr
  "Guarded so re-running `bb dev` against an already-running system is a
   no-op rather than an error."
  "(do (require 'dev 'integrant.repl 'integrant.repl.state)
       (if integrant.repl.state/system
         :already-running
         (do (integrant.repl/go) :started)))")

(defn dev-alias-missing?
  "True when an nREPL eval error looks like the running REPL can't load
   the `dev` namespace — i.e. it was jacked in without the `:dev` alias."
  [err]
  (boolean (re-find #"(?i)locate dev" (or err ""))))

(defn dev!
  "Bring up the dev system, reusing a running REPL when one is advertised.

   The 0-arity wires the real seams; the 4-arity takes them as arguments
   so the routing can be tested without a socket or a JVM:
     port-fn  -> advertised nREPL port, or nil
     boot-fn  -> (fn [port]) evals the boot, returns {:value :err},
                 throws ConnectException on a stale port
     serve-fn -> start our own dev JVM (blocks)
     say-fn   -> print a line"
  ([] (dev! repl/read-port
            #(repl/eval-expr % boot-expr)
            #(dep/sh! "clojure" "-M:dev")
            println))
  ([port-fn boot-fn serve-fn say-fn]
   (if-let [port (port-fn)]
     (try
       (let [{:keys [value err]} (boot-fn port)]
         (cond
           (and err (dev-alias-missing? err))
           (do (say-fn (str "A REPL is running on " port " but lacks the :dev alias."))
               (say-fn "Re-jack-in with :dev (it carries integrant.repl + embedded-postgres)."))

           err
           (say-fn (str "Boot failed in the REPL on " port ": " err))

           :else
           (say-fn (str "Dev system " (or value ":started")
                        " in the REPL on port " port "."))))
       (catch ConnectException _
         (say-fn (str "Stale " repl/port-file " (port " port " not listening);"
                      " starting a fresh dev system ..."))
         (serve-fn)))
     (do (say-fn "No REPL advertised; starting a fresh dev system ...")
         (serve-fn)))))
