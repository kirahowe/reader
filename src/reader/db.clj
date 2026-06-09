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

(def ^:private credential-url-re
  ;; A postgres URL carrying credentials in the libpq `user:password@host`
  ;; authority. The `jdbc:` prefix is optional; the `@` makes the match
  ;; conditional on credentials actually being present, so credential-free
  ;; URLs (dev's embedded Postgres) fall through untouched.
  #"^(?:jdbc:)?postgres(?:ql)?://([^/@]+)@([^?]+)(?:\?(.*))?$")

(defn- normalize-spec
  "Reshape the datasource spec so pgjdbc accepts its `:jdbc-url`.

   Neon hands you a libpq connection URI —
   `postgresql://user:password@host/db?sslmode=require&channel_binding=require`
   — but the PostgreSQL JDBC driver parses neither credentials in the
   authority nor the libpq-only `channel_binding` parameter, so HikariCP
   fails with \"Failed to get driver instance\" the first time it opens a
   connection. Lift any embedded credentials into `:username`/`:password`,
   strip `channel_binding`, and emit a `jdbc:postgresql://host/db` URL the
   driver understands. A credential-free URL is returned unchanged."
  [{:keys [jdbc-url] :as spec}]
  (if-let [[_ userinfo host-path query]
           (and jdbc-url (re-matches credential-url-re jdbc-url))]
    (let [[user pass] (str/split userinfo #":" 2)
          query'      (some->> (some-> query (str/split #"&"))
                               (remove #(str/starts-with? % "channel_binding="))
                               seq
                               (str/join "&"))]
      (assoc spec
             :jdbc-url (cond-> (str "jdbc:postgresql://" host-path)
                         query' (str "?" query'))
             :username user
             :password pass))
    spec))

(defmethod ig/init-key :reader.db/datasource [_ {:keys [spec]}]
  (log/info "datasource starting" {:jdbc-url (redact-url (:jdbc-url spec))})
  (let [ds (connection/->pool HikariDataSource (update-keys (normalize-spec spec) kebab->camel))]
    (log/info "datasource started" {:pool-name (:pool-name spec)})
    ds))

(defmethod ig/halt-key! :reader.db/datasource [_ ^HikariDataSource ds]
  (log/info "datasource stopping")
  (.close ds))
