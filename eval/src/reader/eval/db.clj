(ns reader.eval.db
  "The evals app's own migrator (ADR 0006). Applies the eval_* migrations on a
   separate migratus tracking table (eval_schema_migrations) so it never contends
   with the reader's schema_migrations in the shared database. Depends on the
   reader migrator — so the public schema it reads from is present first — and
   returns the same datasource, the `:reader.eval.db/migrator` other eval
   components depend on to mean \"both schemas are ready.\""
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [migratus.core :as migratus]))

(defmethod ig/init-key :reader.eval.db/migrator
  [_ {:keys [datasource migrations-path migration-table-name migrate-on-init?]
      :or   {migrate-on-init? true}}]
  (if migrate-on-init?
    (do (log/info "eval migrator starting" {:migrations-path migrations-path})
        (migratus/migrate {:store                :database
                           :migration-dir        migrations-path
                           :migration-table-name migration-table-name
                           :db                   {:datasource datasource}})
        (log/info "eval migrator done"))
    (log/info "eval migrator skipping on-boot migrate (run via release_command)"))
  datasource)

(defmethod ig/halt-key! :reader.eval.db/migrator [_ _])
