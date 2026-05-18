(ns reader.main
  "Entry point. Loads, preps, and starts the Integrant system from
  a list of EDN profiles layered with meta-merge. The first arg to
  `-main` (if given) is treated as the name of an extra profile
  resource that is appended to `core-profiles`."
  (:require [clojure.java.io :as io]
            [com.brunobonacci.mulog :as mu]
            [integrant.core :as ig]
            [meta-merge.core :as mm]
            [reader.concerns.integrant :as igc])
  (:gen-class))

(defn read-config [config]
  (ig/read-string {:readers igc/readers} config))

(defn load-config
  "Reads a single profile to a config map. Accepts:
    - a config map (returned as-is)
    - nil (returns {})
    - a string filename (resolved on the classpath via io/resource)
    - any other slurpable value (URL, File, ...)."
  [profile]
  (cond
    (map? profile)    profile
    (nil? profile)    {}
    (string? profile) (->> profile io/resource slurp read-config)
    :else             (->> profile slurp read-config)))

(defn merge-profiles [profiles]
  (apply mm/meta-merge (map load-config profiles)))

(defn prep-config
  "Reads, merges, and loads namespaces for `profiles`. Returns the
  merged config map ready for `ig/init`."
  [profiles]
  (let [config (doto (merge-profiles profiles)
                 (ig/load-namespaces))]
    (mu/log ::config-prepped :profiles profiles)
    config))

(def core-profiles [(io/resource "base-system.edn")])

(defn exec-config
  "Preps and inits the given profiles. Returns the running system."
  [profiles]
  (-> profiles prep-config ig/init))

(defn- on-shutdown! [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable f)))

(defn -main [& args]
  (mu/log ::starting :args args)
  (let [profiles (if-let [supplied (first args)]
                   (concat core-profiles [supplied])
                   core-profiles)
        system   (exec-config profiles)]
    (on-shutdown! #(do (mu/log ::shutdown)
                       (ig/halt! system)))
    (mu/log ::ready)))
