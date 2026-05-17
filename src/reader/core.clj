(ns reader.core
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [reader.sys :as sys])
  (:gen-class))

(def ^:private default-configs
  ["base-system.edn" "env.edn"])

(defn start
  "Loads, preps, and inits the system from the given list of classpath
   resources. Returns the running system."
  ([] (start default-configs))
  ([configs]
   (-> configs sys/load-configs sys/prep-config ig/init)))

(defn stop [system]
  (ig/halt! system))

(defn -main [& _args]
  (mu/log ::starting)
  (let [system (start)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                               (fn []
                                 (mu/log ::shutdown)
                                 (stop system))))
    (mu/log ::ready)
    @(promise)))
