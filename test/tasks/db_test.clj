(ns tasks.db-test
  "Tests for the `db` bb-task namespace — pure flag/reply predicates plus
   the `seed!` orchestrator, exercised through injected IO seams so no
   tty, socket, or `System/exit` is involved."
  (:require [clojure.test :refer [deftest is testing]]
            [db]))

(deftest forced?-recognises-skip-flags
  (testing "any of the force flags counts as forced"
    (is (db/forced? ["--yes"]))
    (is (db/forced? ["-y"]))
    (is (db/forced? ["--force"]))
    (is (db/forced? ["something" "--yes"])))
  (testing "no flag, empty args, or an unrelated flag is not forced"
    (is (not (db/forced? [])))
    (is (not (db/forced? nil)))
    (is (not (db/forced? ["--no"])))))

(deftest affirmative?-only-yes-passes
  (testing "y/yes in any case and with surrounding space count as yes"
    (is (db/affirmative? "y"))
    (is (db/affirmative? "yes"))
    (is (db/affirmative? "  Yes "))
    (is (db/affirmative? "YES")))
  (testing "blank, nil, and anything else count as no"
    (is (not (db/affirmative? "")))
    (is (not (db/affirmative? nil)))
    (is (not (db/affirmative? "n")))
    (is (not (db/affirmative? "no")))
    (is (not (db/affirmative? "yeah")))))

(deftest seed!-aborts-on-a-no-reply
  (testing "a non-affirmative reply never evals and never exits"
    (let [says  (atom [])
          evals (atom 0)
          exits (atom [])]
      (db/seed! []
                (constantly "n")
                (fn [] (swap! evals inc) {:value ":seeded" :err nil})
                (fn [m] (swap! says conj m))
                (fn [c] (swap! exits conj c)))
      (is (zero? @evals) "must not touch the db when the user declines")
      (is (= [] @exits))
      (is (some #(re-find #"Aborted" %) @says)))))

(deftest seed!-proceeds-on-a-yes-reply
  (testing "an affirmative reply runs the seed and reports the result"
    (let [says  (atom [])
          evals (atom 0)
          exits (atom [])]
      (db/seed! []
                (constantly "y")
                (fn [] (swap! evals inc) {:value ":seeded" :err nil})
                (fn [m] (swap! says conj m))
                (fn [c] (swap! exits conj c)))
      (is (= 1 @evals))
      (is (= [] @exits))
      (is (some #(re-find #"Result: :seeded" %) @says)))))

(deftest seed!-force-flag-skips-the-prompt
  (testing "--yes seeds without ever calling the prompt fn"
    (let [asked (atom 0)
          evals (atom 0)]
      (db/seed! ["--yes"]
                (fn [] (swap! asked inc) "n")
                (fn [] (swap! evals inc) {:value ":seeded" :err nil})
                (constantly nil)
                (constantly nil))
      (is (zero? @asked) "the prompt must be skipped entirely when forced")
      (is (= 1 @evals)))))

(deftest seed!-reports-and-exits-on-eval-error
  (testing "an error from the eval is surfaced and exits non-zero"
    (let [says  (atom [])
          exits (atom [])]
      (db/seed! ["--yes"]
                (constantly "y")
                (constantly {:value nil :err "boom"})
                (fn [m] (swap! says conj m))
                (fn [c] (swap! exits conj c)))
      (is (= [1] @exits))
      (is (some #(re-find #"Error: boom" %) @says)))))

(deftest seed!-handles-a-dead-nrepl
  (testing "a ConnectException becomes an actionable message and exit 1"
    (let [says  (atom [])
          exits (atom [])]
      (db/seed! ["--yes"]
                (constantly "y")
                (fn [] (throw (java.net.ConnectException. "refused")))
                (fn [m] (swap! says conj m))
                (fn [c] (swap! exits conj c)))
      (is (= [1] @exits))
      (is (some #(re-find #"Could not connect" %) @says))
      (is (some #(re-find #"bb dev" %) @says)))))
