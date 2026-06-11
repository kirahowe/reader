(ns reader.migrate
  "Migration entrypoint for the Fly release_command. Layers `migrate.edn`
   over the app profiles and inits only the migrator, applying pending
   migrations once per deploy. Exits 0 on success, 1 on failure (a broken
   migration fails the deploy)."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [reader.main :as main])
  (:gen-class))

(defn -main [& args]
  (let [profiles (concat (main/profiles-from-args args) ["migrate.edn"])
        config   (main/prep-config profiles)]
    (try
      (-> config
          (ig/init [:reader.log/publisher :reader.db/migrator])
          ig/halt!)
      (log/info "migrate complete (db up to date)")
      (shutdown-agents)
      (System/exit 0)
      (catch Throwable t
        (log/error t "migration failed")
        (shutdown-agents)
        (System/exit 1)))))
