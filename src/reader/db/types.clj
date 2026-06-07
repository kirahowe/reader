(ns reader.db.types
  "Postgres <-> Clojure value codec for next.jdbc:

    - `->jsonb` encodes a map/vector into a `jsonb` PGobject. Callers
      encode explicitly (see `reader.db.crud`) so the write path stays
      visible at the call site rather than happening at JDBC bind time.
    - `jsonb`/`json` PGobjects unmarshal back into Clojure data on read,
      via the `ReadableColumn` extension below (charred, keyword keys).
    - requiring `next.jdbc.date-time` installs, as a side effect, the
      extension that binds `java.time.Instant` to `timestamptz`.

   Required from `reader.db` so loading the datasource pulls all three in."
  (:require [charred.api :as json]
            [next.jdbc.date-time]
            [next.jdbc.result-set :as rs])
  (:import (org.postgresql.util PGobject)))

(def ^:private read-json
  "Parse a JSON string into Clojure data with keyword keys. Precompiled
   per charred's guidance; the returned fn is thread-safe."
  (json/parse-json-fn {:key-fn keyword}))

(defn ->jsonb
  "Encode a Clojure value into a Postgres `jsonb` PGobject. Opinionated:
   only hand this a value destined for a jsonb column."
  ^PGobject [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-json-str x))))

(defn- jsonb->clj [^PGobject obj]
  (when-let [v (.getValue obj)]
    (read-json v)))

(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [obj _]
    (case (.getType obj)
      ("json" "jsonb") (jsonb->clj obj)
      "citext"         (.getValue obj)
      obj))
  (read-column-by-index [obj _ _]
    (case (.getType obj)
      ("json" "jsonb") (jsonb->clj obj)
      "citext"         (.getValue obj)
      obj)))
