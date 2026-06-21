(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/reader.jar")

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar [_]
  (clean nil)
  (let [b (basis)]
    (b/copy-dir {:src-dirs   ["src" "resources"]
                 :target-dir class-dir})
    ;; AOT every app namespace, not just the entry points. The system is wired in
    ;; EDN and its namespaces are pulled in at runtime by ig/load-namespaces, so
    ;; they're invisible to a main-rooted compile graph and would otherwise be
    ;; compiled from source on every JVM cold start. Compiling all of src moves
    ;; that cost to build time. With no :ns-compile, every namespace under
    ;; :src-dirs is compiled.
    (b/compile-clj {:basis     b
                    :src-dirs  ["src"]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     b
             :main      'reader.main}))
  (println "built" uber-file))
