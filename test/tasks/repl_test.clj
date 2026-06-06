(ns tasks.repl-test
  "Tests for the shared `repl` bb-task namespace: `.nrepl-port` parsing
   and the connection decision, exercised through pure functions."
  (:require [clojure.test :refer [deftest is testing]]
            [repl]))

(deftest parse-port-reads-a-clean-integer
  (testing "a bare port number parses"
    (is (= 7888 (repl/parse-port "7888"))))
  (testing "surrounding whitespace/newline is ignored"
    (is (= 54824 (repl/parse-port "  54824\n")))))

(deftest parse-port-rejects-non-ports
  (testing "blank, nil, and non-numeric content yield nil"
    (is (nil? (repl/parse-port "")))
    (is (nil? (repl/parse-port "   ")))
    (is (nil? (repl/parse-port nil)))
    (is (nil? (repl/parse-port "not-a-port")))))
