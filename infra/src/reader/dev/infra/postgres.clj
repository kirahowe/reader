(ns reader.dev.infra.postgres
  "Embedded-Postgres-managed local database for dev and tests. The
   Integrant component owns the postgres subprocess lifecycle: init
   starts it on an ephemeral port and returns a connection spec the
   `:reader.db/datasource` consumes; halt stops the subprocess.

   First-run cost ~1.5s while the postgres binary is unpacked into
   `~/.embedded-postgres-binaries`; subsequent runs are ~500ms warm."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(defn- builder
  "A builder for the embedded cluster. With a `data-dir`, postgres reuses that
   directory and keeps it across starts, so a dev DB survives a server restart
   or REPL `(reset)`; without one, each start gets a fresh throwaway dir (tests)."
  [data-dir]
  (let [b (EmbeddedPostgres/builder)]
    (when data-dir
      (.setDataDirectory b (io/file data-dir))
      (.setCleanDataDirectory b false))
    b))

(defmethod ig/init-key :reader.dev.infra/postgres [_ {:keys [data-dir]}]
  (log/info "embedded-postgres starting" {:data-dir data-dir})
  (let [pg   (.start (builder data-dir))
        port (.getPort pg)]
    (log/info "embedded-postgres started" {:port port})
    ;; The handle lives in metadata so consumers (HikariCP, any logger that
    ;; prints the spec) see only the JDBC connection fields.
    (with-meta {:jdbc-url (str "jdbc:postgresql://localhost:" port "/postgres")
                :username "postgres"
                :password "postgres"}
      {::handle pg})))

(defmethod ig/halt-key! :reader.dev.infra/postgres [_ spec]
  (log/info "embedded-postgres stopping")
  (.close ^EmbeddedPostgres (::handle (meta spec))))
