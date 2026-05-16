(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'kira/reader)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
(def uber-file "target/reader.jar")

(defn- basis []
  (b/create-basis {:project "deps.edn" :aliases [:prod]}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar [_]
  (clean nil)
  (let [b (basis)]
    (b/copy-dir {:src-dirs   (:paths b)
                 :target-dir class-dir})
    (b/compile-clj {:basis      b
                    :ns-compile '[kira.reader.main]
                    :class-dir  class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     b
             :main      'kira.reader.main}))
  (println "built" uber-file))
