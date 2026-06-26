(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/reader.jar")
(def eval-uber-file "target/reader-eval.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn- build-uber
  "Build an uberjar: copy `src-dirs` to the class dir, AOT-compile `compile-dirs`,
   and pack a `main`-rooted uber from `basis`.

   AOT covers every app namespace, not just the entry point: the system is wired
   in EDN and its namespaces are pulled in at runtime by ig/load-namespaces, so
   they're invisible to a main-rooted compile graph and would otherwise be
   compiled from source on every JVM cold start. Moving that to build time keeps
   cold starts fast. With no :ns-compile, every namespace under :src-dirs compiles."
  [{:keys [basis src-dirs compile-dirs main uber]}]
  (clean nil)
  (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
  (b/compile-clj {:basis basis :src-dirs compile-dirs :class-dir class-dir})
  (b/uber {:class-dir class-dir :uber-file uber :basis basis :main main})
  (println "built" uber))

(defn uberjar [_]
  (build-uber {:basis        (b/create-basis {:project "deps.edn"})
               :src-dirs     ["src" "resources"]
               :compile-dirs ["src"]
               :main         'reader.main
               :uber         uber-file}))

(defn eval-uberjar
  "The evals app image (ADR 0006): a superset of the reader — its domain + infra
   plus eval/src, eval/resources, and the :eval alias's deps (Datastar). Rooted at
   reader.eval.main; eval-prod.edn ships inside the jar as a classpath resource."
  [_]
  (build-uber {:basis        (b/create-basis {:project "deps.edn" :aliases [:eval]})
               :src-dirs     ["src" "resources" "eval/src" "eval/resources"]
               :compile-dirs ["src" "eval/src"]
               :main         'reader.eval.main
               :uber         eval-uber-file}))
