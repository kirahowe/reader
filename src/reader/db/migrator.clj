(ns reader.db.migrator
  "The `:reader.db/migrator` component. Runs every pending migration
   from `resources/migrations/` against the supplied datasource on
   init. Returns the datasource so other components can express
   \"depend on a migrated database\" by `#ig/ref`-ing this key instead
   of `:reader.db/datasource`. Halt is a no-op — migrations have no
   live state to hold; the datasource is halted by its own key.

   `:migrate-on-init?` (default true) gates the on-boot migration. Dev and
   test leave it on; prod sets it false and migrates out-of-band via the
   release_command (see `reader.migrate`)."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [migratus.core :as migratus]))

(defn- migratus-config [datasource migrations-path]
  {:store         :database
   :migration-dir migrations-path
   :db            {:datasource datasource}})

(defmethod ig/init-key :reader.db/migrator
  [_ {:keys [datasource migrations-path migrate-on-init?]
      :or   {migrate-on-init? true}}]
  (if migrate-on-init?
    (do (log/info "migrator starting" {:migrations-path migrations-path})
        (migratus/migrate (migratus-config datasource migrations-path))
        (log/info "migrator done"))
    (log/info "migrator skipping on-boot migrate (run via release_command)"))
  datasource)

(defmethod ig/halt-key! :reader.db/migrator [_ _])
