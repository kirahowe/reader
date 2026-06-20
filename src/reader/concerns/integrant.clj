(ns reader.concerns.integrant
  "Integrant concerns: EDN reader literals and constant-value
  init-keys. Required (transitively) by any entry point that reads
  the system config or calls `ig/load-namespaces`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]))

;; ---------- Constants ----------

(defmethod ig/init-key :reader/const [_ v] v)

;; Derive each constant key from :reader/const so its value flows
;; through the init-method above and can be `#ig/ref`'d by components.
;; e.g. (derive :reader/base-url :reader/const)

;; The local-filesystem blob-storage root (the :file backend). A plain EDN
;; constant so the path is configured per-profile, never hardcoded in code.
(derive :reader.storage/file-root :reader/const)

;; The inbound-email domain — the host part of every user's alias. A plain EDN
;; constant so the settings show + rotate handlers share one per-profile value
;; (they must mint at the same domain) instead of duplicating the override.
(derive :reader.inbound/domain :reader/const)

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

(defn- csv->set
  "Comma-separated string -> set of trimmed, non-blank entries (empty set for
   nil or blank input)."
  [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       set))

(defn env-set
  "#env/set -- optional env var split on commas into a set of trimmed,
   non-blank strings (empty set when unset). Used for the prod invite
   allowlist so testers can be added via a Fly secret, no redeploy."
  [arg]
  (csv->set (env-opt arg)))

(def readers
  {'env        env
   'env/opt    env-opt
   'env/long   env-long
   'env/bool   env-bool
   'env/secret env-secret
   'env/set    env-set
   'resource   io/resource})
