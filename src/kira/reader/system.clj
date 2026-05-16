(ns kira.reader.system
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [meta-merge.core :as mm]
            [kira.reader.config-readers :as readers]))

(def ^:private edn-readers
  {'ig/ref     ig/ref
   'ig/refset  ig/refset
   'env        readers/read-env
   'env/opt    readers/read-env-opt
   'env/long   readers/read-env-long
   'env/bool   readers/read-env-bool
   'env/secret readers/read-env-secret})

(defn- read-edn-resource [path]
  (when-let [r (io/resource path)]
    (edn/read-string {:readers edn-readers} (slurp r))))

(defn config
  "Reads the base system config and meta-merges the active overlay
   (whichever `system/overlay.edn` happens to be on the classpath,
   determined by the active deps.edn alias). Keys whose merged value
   is nil are dropped so overlays can remove components by setting
   them to nil."
  []
  (let [base    (read-edn-resource "system/base.edn")
        overlay (read-edn-resource "system/overlay.edn")]
    (when-not base
      (throw (ex-info "system/base.edn not found on classpath" {})))
    (into {}
          (remove (comp nil? val))
          (mm/meta-merge base (or overlay {})))))
