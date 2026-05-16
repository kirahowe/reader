(ns kira.reader.main
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [kira.reader.system :as system])
  (:gen-class))

(defn -main [& _args]
  (let [config (system/config)]
    (ig/load-namespaces config)
    (let [sys (ig/init config)]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable
                                 (fn []
                                   (mu/log ::shutdown)
                                   (ig/halt! sys))))
      (mu/log ::ready)
      @(promise))))
