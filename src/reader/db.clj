(ns reader.db
  "The app's HikariCP connection pool — `:reader.db/datasource`."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [next.jdbc.connection :as connection]
            [reader.db.types]) ; load jsonb / Instant protocol extensions
  (:import (com.zaxxer.hikari HikariDataSource)))

(defn- kebab->camel
  "kebab-case keyword → camelCase. Our datasource spec is kebab-case like
   the rest of the config, but `->pool` hands keys straight to HikariCP's
   camelCase setters. We control the spec, so each key's kebab form is
   exactly its Hikari property — convert at this one boundary."
  [k]
  (let [[head & tail] (str/split (name k) #"-")]
    (keyword (apply str head (map str/capitalize tail)))))

(defn- redact-url
  "Strip any `user:password@` from a JDBC URL before it reaches the log.
   The prod Neon URL carries credentials; dev's embedded URL has none
   and passes through unchanged."
  [jdbc-url]
  (some-> jdbc-url (str/replace #"//[^@/]+@" "//")))

(defmethod ig/init-key :reader.db/datasource [_ {:keys [spec]}]
  (log/info "datasource starting" {:jdbc-url (redact-url (:jdbc-url spec))})
  (let [ds (connection/->pool HikariDataSource (update-keys spec kebab->camel))]
    (log/info "datasource started" {:pool-name (:pool-name spec)})
    ds))

(defmethod ig/halt-key! :reader.db/datasource [_ ^HikariDataSource ds]
  (log/info "datasource stopping")
  (.close ds))
