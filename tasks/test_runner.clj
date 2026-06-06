(ns test-runner
  "Runs the bb-task test suite under bb itself. Necessary because the
   tested namespaces (`db`, `depends`, `dev-task`, `fly`, `repl`) use
   bb's built-in stdlib (`bencode.core`, `babashka.fs`,
   `babashka.process`) which isn't available under the JVM-Clojure /
   kaocha runner."
  (:require [clojure.test :as t]
            tasks.db-test
            tasks.depends-test
            tasks.dev-test
            tasks.fly-test
            tasks.repl-test))

(defn run-tests []
  (let [{:keys [fail error]} (t/run-tests 'tasks.db-test
                                          'tasks.depends-test
                                          'tasks.dev-test
                                          'tasks.fly-test
                                          'tasks.repl-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
