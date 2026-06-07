(ns reader.db.crud
  "Generic, data-driven CRUD against any table via HoneySQL + next.jdbc.
   App code uses kebab-case keys and table names; HoneySQL converts on
   the way out, the result-set builder converts on the way in. Tables
   that need real domain logic get their own namespace; bare CRUD
   lives here."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [reader.db.types :as types]))

(def opts
  "next.jdbc options every db call in this app threads through:
   kebab-case qualified keys on returned rows."
  {:builder-fn rs/as-kebab-maps})

(defn- ->sql-value
  "Encode a CRUD value for HoneySQL. A map or vector is a jsonb value, so
   run it through the jsonb codec — the resulting PGobject is an opaque
   literal HoneySQL passes through, instead of a raw collection it would
   try to parse as a DSL fragment. Scalars pass straight through. Applies
   to both write values and `where` predicates so the two stay symmetric."
  [v]
  (if (or (map? v) (vector? v)) (types/->jsonb v) v))

(defn- encode-values [attrs]
  (reduce-kv (fn [m k v] (assoc m k (->sql-value v))) {} attrs))

(defn- where-clause [where]
  (when (seq where)
    (into [:and] (for [[col v] where] [:= col (->sql-value v)]))))

(defn by-id [ds table id]
  (jdbc/execute-one! ds
                     (sql/format {:select [:*] :from [table] :where [:= :id id]})
                     opts))

(defn find-many
  ([ds table] (find-many ds table {}))
  ([ds table where]
   (jdbc/execute! ds
                  (sql/format (cond-> {:select [:*] :from [table]}
                                (seq where) (assoc :where (where-clause where))))
                  opts)))

(defn find-in
  "Rows of `table` whose `col` is one of `vals`. Empty `vals` short-circuits
   to `[]` — there is nothing to match, and `IN ()` is invalid SQL anyway."
  [ds table col vals]
  (if (seq vals)
    (jdbc/execute! ds
                   (sql/format {:select [:*] :from [table] :where [:in col vals]})
                   opts)
    []))

(defn find-1
  "Like `find-many` but expects the `where` to identify at most one row.
   Returns nil for no match, the row for exactly one, and throws
   ExceptionInfo on more than one (LIMIT 2 detects the multi-match cheaply
   rather than silently picking an arbitrary winner). Callers wanting
   one-of-many should use `find-many` and choose."
  [ds table where]
  (let [rows (jdbc/execute! ds
                            (sql/format (cond-> {:select [:*] :from [table] :limit 2}
                                          (seq where) (assoc :where (where-clause where))))
                            opts)]
    (case (count rows)
      0 nil
      1 (first rows)
      (throw (ex-info "find-1 matched more than one row"
                      {:table table :where where})))))

(defn create! [ds table attrs]
  (jdbc/execute-one! ds
                     (sql/format {:insert-into [table]
                                  :values      [(encode-values attrs)]
                                  :returning   [:*]})
                     opts))

(defn upsert!
  "Insert `attrs`; if it collides on the unique `conflict-cols`, update the
   existing row with `update-set` instead of erroring. Returns the inserted
   or updated row. `update-set` values are HoneySQL expressions passed through
   verbatim (e.g. `[:now]`), so any jsonb belongs in `attrs` (the insert side)
   where `encode-values` will codec it — not in `update-set`."
  [ds table attrs conflict-cols update-set]
  (jdbc/execute-one! ds
                     (sql/format {:insert-into   [table]
                                  :values        [(encode-values attrs)]
                                  :on-conflict   conflict-cols
                                  :do-update-set update-set
                                  :returning     [:*]})
                     opts))

(defn update! [ds table id attrs]
  (jdbc/execute-one! ds
                     (sql/format {:update    table
                                  :set       (encode-values attrs)
                                  :where     [:= :id id]
                                  :returning [:*]})
                     opts))

(defn delete! [ds table id]
  (jdbc/execute-one! ds
                     (sql/format {:delete-from table
                                  :where       [:= :id id]
                                  :returning   [:*]})
                     opts))
