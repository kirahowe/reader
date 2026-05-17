(ns reader.sys
  "Loads an Integrant system from one or more EDN resources on the
   classpath, layering them with meta-merge. Modelled on the pattern
   in tpximpact.datahost.sys."
  (:require [meta-merge.core :as mm]
            [integrant.core :as ig]
            [reader.config-readers :as r]))

(def ^:private readers
  {'env        r/read-env
   'env/opt    r/read-env-opt
   'env/long   r/read-env-long
   'env/bool   r/read-env-bool
   'env/secret r/read-env-secret})

(defn- load-resource [resource]
  (some->> resource slurp (ig/read-string {:readers readers})))

(defn- classpath-resources [resource-name]
  (-> (Thread/currentThread)
      .getContextClassLoader
      (.getResources resource-name)
      enumeration-seq))

(defn load-configs
  "Reads every classpath instance of each name in `resource-names`
   (in order) and meta-merges them. Resource names later in the list
   override those earlier in the list. Keys whose merged value is
   nil are dropped so an overlay can remove a component."
  [resource-names]
  (let [merged (->> resource-names
                    (mapcat classpath-resources)
                    (map load-resource)
                    (apply mm/meta-merge))]
    (into {} (remove (comp nil? val)) merged)))

(defn prep-config
  "Loads namespaces referenced by the config and runs `ig/prep`."
  [config]
  (-> config (doto ig/load-namespaces) ig/prep))
