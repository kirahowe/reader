(ns reader.config-readers
  "EDN reader literals for resolving env vars inside the system config.")

(defrecord Secret [value]
  Object
  (toString [_] "<secret>"))

(defmethod print-method Secret [_ ^java.io.Writer w]
  (.write w "#secret \"<redacted>\""))

(defn- env*
  "arg is either \"NAME\" or [\"NAME\" default]."
  [arg]
  (let [[name default] (if (vector? arg) arg [arg nil])]
    (or (System/getenv name) default)))

(defn read-env [arg]
  (or (env* arg)
      (throw (ex-info (str "missing required env var: " arg) {:env arg}))))

(defn read-env-opt [arg]
  (env* arg))

(defn read-env-long [arg]
  (some-> (read-env arg) str Long/parseLong))

(defn read-env-bool [arg]
  (contains? #{"true" "1" "yes"} (some-> (read-env arg) str)))

(defn read-env-secret [arg]
  (->Secret (read-env arg)))
