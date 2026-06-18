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

(defn create-ignore!
  "Insert `attrs`, returning the new row — or nil if it collided with an existing
   row on any unique constraint (`ON CONFLICT DO NOTHING`). Lets a caller treat a
   lost insert race as \"someone else just created it\" and re-resolve, instead of
   aborting the transaction on the unique violation a plain `create!` would raise."
  [ds table attrs]
  (jdbc/execute-one! ds
                     (sql/format {:insert-into [table]
                                  :values      [(encode-values attrs)]
                                  :on-conflict []
                                  :do-nothing  []
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

(defn update-where!
  "Update rows of `table` matching the HoneySQL `where` form, returning the
   updated row (nil if none matched). `attrs` are encoded like `update!`."
  [ds table where attrs]
  (jdbc/execute-one! ds
                     (sql/format {:update    table
                                  :set       (encode-values attrs)
                                  :where     where
                                  :returning [:*]})
                     opts))

(defn update! [ds table id attrs]
  (update-where! ds table [:= :id id] attrs))

(defn delete! [ds table id]
  (jdbc/execute-one! ds
                     (sql/format {:delete-from table
                                  :where       [:= :id id]
                                  :returning   [:*]})
                     opts))

;; ── identity-aware upsert (canonical entity resolution) ──────────────────

(defn- blank-fills
  "The subset of `attrs` (unqualified keys) that would fill a currently-nil
   column on `row` (keys qualified by `q`) — new info to add, never a clobber."
  [q row attrs]
  (reduce-kv (fn [m k v]
               (if (and (some? v) (nil? (get row (keyword q (name k)))))
                 (assoc m k v)
                 m))
             {}
             attrs))

(defn- free-slug
  "A `slug-col` value not yet taken in `table`: `base`, else base-2, base-3, …"
  [ds table slug-col base]
  (loop [candidate base n 2]
    (if (find-1 ds table {slug-col candidate})
      (recur (str base "-" n) (inc n))
      candidate)))

(defn resolve-entity!
  "Identity-aware upsert for canonical graph entities (authors, institutions).
   Matches `attrs` against existing rows by each key in `id-keys` (priority
   order, only keys present + non-nil in attrs), else by `slug-key`. A slug match
   is rejected when it conflicts with a supplied id (the row holds a different
   non-nil value for an id-key) — distinct entities that share a name don't
   merge. On a match, fills columns that are nil on the row from attrs (never
   clobbering) and returns it; on no match, inserts, disambiguating `slug-key`
   (suffix) when taken.

   Race-safe across concurrent workers: the insert is `ON CONFLICT DO NOTHING`,
   so if another worker created the same entity in the gap between our lookup and
   our insert, we re-resolve (the row now exists) rather than aborting on the
   unique violation. Runs several statements — pass a transaction for atomicity."
  [ds table {:keys [id-keys slug-key attrs]}]
  (let [q          (name table)
        conflicts? (fn [row]
                     (boolean (some (fn [k]
                                      (let [ours (get attrs k) theirs (get row (keyword q (name k)))]
                                        (and ours theirs (not= ours theirs))))
                                    id-keys)))
        find-match (fn []
                     (let [by-id    (some (fn [k] (when-let [v (get attrs k)] (find-1 ds table {k v}))) id-keys)
                           slug-row (when-let [s (get attrs slug-key)] (find-1 ds table {slug-key s}))]
                       (or by-id (when (and slug-row (not (conflicts? slug-row))) slug-row))))]
    (loop [attempt 0]
      (if-let [match (find-match)]
        (let [fills (blank-fills q match attrs)]
          (if (seq fills)
            (update! ds table (get match (keyword q "id")) (assoc fills :updated-at (java.time.Instant/now)))
            match))
        (or (create-ignore! ds table (assoc attrs slug-key (free-slug ds table slug-key (get attrs slug-key))))
            ;; Lost the insert race: the row now exists (or its slug is now taken),
            ;; so loop to find/disambiguate it. Bounded — a persistent miss is a bug.
            (if (< attempt 3)
              (recur (inc attempt))
              (throw (ex-info "resolve-entity! did not converge"
                              {:table table :attrs attrs}))))))))
