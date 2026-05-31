(ns reader.dev.main
  "Entry point for `bb dev`. Drives the Integrant lifecycle via
   `integrant.repl` so the running system is accessible at
   `integrant.repl.state/system` for nREPL-driven tools (e.g. the
   `bb db:seed` task), and binds an nREPL server on `:7888`."
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.repl :as igr]
            [integrant.repl.state :as igs]
            [nrepl.server :as nrepl]
            [reader.main :as main])
  (:gen-class))

(def nrepl-port 7888)

(def profiles
  (concat main/core-profiles ["embedded-db.edn" "dev.edn"]))

(defn -main [& _]
  (igr/set-prep! #(main/prep-config profiles))
  (igr/go)
  (let [server (nrepl/start-server :port nrepl-port)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                               (fn []
                                 (mu/log ::shutdown)
                                 (nrepl/stop-server server)
                                 (when igs/system (igr/halt)))))
    (mu/log ::ready :nrepl-port (:port server))
    (println "Reader dev ready. nREPL on" (:port server))))
