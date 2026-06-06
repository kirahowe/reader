(ns reader.dev.main
  "Entry point for `bb dev` when no REPL is already running. Drives the
   Integrant lifecycle via `integrant.repl` so the running system is
   reachable at `integrant.repl.state/system` for nREPL-driven tools
   (e.g. the `bb db:seed` task), and binds a cider-nrepl server on an
   OS-assigned port, advertising it in `.nrepl-port` so editors and bb
   tasks discover and connect to it — no hardcoded port."
  (:require [cider.nrepl :refer [cider-nrepl-handler]]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as mu]
            [integrant.repl :as igr]
            [integrant.repl.state :as igs]
            [nrepl.server :as nrepl]
            [reader.main :as main])
  (:gen-class))

(def port-file ".nrepl-port")

(def profiles
  (concat main/core-profiles ["embedded-db.edn" "dev.edn"]))

(defn -main [& _]
  (igr/set-prep! #(main/prep-config profiles))
  (igr/go)
  (let [server (nrepl/start-server :port 0 :handler cider-nrepl-handler)
        port   (:port server)]
    (spit port-file (str port))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                               (fn []
                                 (mu/log ::shutdown)
                                 (nrepl/stop-server server)
                                 (io/delete-file port-file true)
                                 (when igs/system (igr/halt)))))
    (mu/log ::ready :nrepl-port port)
    (println "Reader dev ready. cider-nrepl on" port "(advertised in" (str port-file ")"))))
