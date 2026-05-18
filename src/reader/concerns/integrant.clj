(ns reader.concerns.integrant
  "Integrant concerns: EDN reader literals and constant-value
  init-keys. Required (transitively) by any entry point that reads
  the system config or calls `ig/load-namespaces`."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]))

;; ---------- Constants ----------

(defmethod ig/init-key :reader/const [_ v] v)

;; Derive each constant key from :reader/const so its value flows
;; through the init-method above and can be `#ig/ref`'d by components.
;; e.g. (derive :reader/base-url :reader/const)

;; ---------- EDN reader literals ----------

(defrecord Secret [value]
  Object
  (toString [_] "<secret>"))

(defmethod print-method Secret [_ ^java.io.Writer w]
  (.write w "#secret \"<redacted>\""))

(defn- env*
  [arg]
  (let [[var-name default] (if (vector? arg) arg [arg nil])]
    (or (System/getenv var-name) default)))

(defn env
  [arg]
  (or (env* arg)
      (throw (ex-info (str "missing required env var: " arg) {:env arg}))))

(defn env-opt
  [arg]
  (env* arg))

(defn env-long
  [arg]
  (some-> (env arg) str Long/parseLong))

(defn env-bool
  [arg]
  (contains? #{"true" "1" "yes"} (some-> (env arg) str)))

(defn env-secret
  "#env/secret -- required env var wrapped in a Secret that redacts itself."
  [arg]
  (->Secret (env arg)))

(def readers
  {'env        env
   'env/opt    env-opt
   'env/long   env-long
   'env/bool   env-bool
   'env/secret env-secret
   'resource   io/resource})
