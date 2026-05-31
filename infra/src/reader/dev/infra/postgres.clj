(ns reader.dev.infra.postgres
  "Embedded-Postgres-managed local database for dev and tests. The
   Integrant component owns the postgres subprocess lifecycle: init
   starts it on an ephemeral port and returns a connection spec the
   `:reader.db/datasource` consumes; halt stops the subprocess.

   First-run cost ~1.5s while the postgres binary is unpacked into
   `~/.embedded-postgres-binaries`; subsequent runs are ~500ms warm."
  (:require [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(defmethod ig/init-key :reader.dev.infra/postgres [_ _]
  (mu/log ::starting)
  (let [pg   (.start (EmbeddedPostgres/builder))
        port (.getPort pg)]
    (mu/log ::started :port port)
    ;; The handle lives in metadata so consumers (HikariCP, mu/log, any
    ;; logger that prints the spec) see only the JDBC connection fields.
    (with-meta {:jdbc-url (str "jdbc:postgresql://localhost:" port "/postgres")
                :username "postgres"
                :password "postgres"}
      {::handle pg})))

(defmethod ig/halt-key! :reader.dev.infra/postgres [_ spec]
  (mu/log ::stopping)
  (.close ^EmbeddedPostgres (::handle (meta spec))))
