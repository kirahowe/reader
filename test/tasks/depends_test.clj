(ns tasks.depends-test
  "Tests for the `depends` namespace — pure builders that produce
   report maps for `say!`, plus the regex-based hint matcher."
  (:require [clojure.test :refer [deftest is testing]]
            [depends :as dep]))

(deftest problem-builds-a-failure-report
  (testing "with both problem and fix"
    (is (= {:lines ["✗ thing missing" "  → run: fix-it"]
            :exit  1}
           (dep/problem "thing missing" "run: fix-it"))))

  (testing "with only a problem"
    (is (= {:lines ["✗ thing missing"]
            :exit  1}
           (dep/problem "thing missing"))))

  (testing "nil fix produces no second line"
    (is (= {:lines ["✗ thing missing"]
            :exit  1}
           (dep/problem "thing missing" nil)))))

(deftest cli-missing-checks-path
  (testing "returns the canonical ok when an executable is present"
    ;; bb is guaranteed on PATH here: bb is running these tests.
    (is (= dep/ok (dep/cli-missing "bb" "https://example.invalid"))))

  (testing "returns a failure report when an executable is absent"
    (let [report (dep/cli-missing "definitely-not-a-real-binary-zzz"
                                  "https://example.invalid")]
      (is (= 1 (:exit report)))
      (is (some #(re-find #"is not installed" %) (:lines report)))
      (is (some #(re-find #"https://example\.invalid" %) (:lines report))))))

(deftest shell->report-handles-success
  (testing "zero exit returns the canonical ok"
    (is (= dep/ok (dep/shell->report {:exit 0 :err ""} nil)))
    (is (= dep/ok (dep/shell->report {:exit 0 :err "anything"}
                                     [{:match #".*" :explain "x"}])))))

(deftest shell->report-handles-failure
  (testing "non-zero exit without hints returns minimal report"
    (let [r (dep/shell->report {:exit 2 :err nil} nil)]
      (is (= 2 (:exit r)))
      (is (= [] (:lines r)))))

  (testing "non-zero exit with hints replays captured stderr"
    (let [r (dep/shell->report {:exit 1 :err "boom\n"} [])]
      (is (= 1 (:exit r)))
      (is (= ["boom\n"] (:lines r)))))

  (testing "matching hint is appended after a blank line"
    (let [r (dep/shell->report
             {:exit 1 :err "Error: Name has already been taken"}
             [{:match #"Name has already been taken"
               :explain "Pick a unique name."}])]
      (is (= 1 (:exit r)))
      (is (= ["Error: Name has already been taken"
              ""
              "  → Pick a unique name."]
             (:lines r)))))

  (testing "first matching hint wins"
    (let [r (dep/shell->report
             {:exit 1 :err "boom"}
             [{:match #"boom" :explain "first"}
              {:match #"boom" :explain "second"}])]
      (is (some #{"  → first"} (:lines r)))
      (is (not (some #{"  → second"} (:lines r))))))

  (testing "no matching hint just replays stderr"
    (let [r (dep/shell->report
             {:exit 1 :err "unknown error"}
             [{:match #"something else" :explain "wont match"}])]
      (is (= ["unknown error"] (:lines r))))))

(deftest ok-is-the-canonical-success-result
  (testing "ok is just an exit-0 marker"
    (is (= {:exit 0} dep/ok))
    (is (zero? (:exit dep/ok)))
    (is (nil? (:lines dep/ok)))))

(deftest say!-on-ok-is-a-no-op
  (testing "ok flows through without exiting (so dep chains keep going)"
    (let [printed (atom [])
          exits   (atom [])
          print!  (fn [x] (swap! printed conj x))
          exit!   (fn [n] (swap! exits conj n))]
      (is (nil? (dep/say! dep/ok print! exit!)))
      (is (= [] @printed) "ok has no lines to print")
      (is (= [] @exits)   "ok must not call the exit fn"))))

(deftest say!-on-failure-prints-and-exits
  (testing "lines are printed and exit-fn is called with the report's exit code"
    (let [printed (atom [])
          exits   (atom [])
          print!  (fn [x] (swap! printed conj x))
          exit!   (fn [n] (swap! exits conj n))
          report  (dep/problem "thing broke" "do the fix")]
      (dep/say! report print! exit!)
      (is (= ["✗ thing broke" "  → do the fix"] @printed))
      (is (= [1] @exits)))))

(deftest sh!-parses-opts-map-vs-bare-args
  (testing "first arg is a string: treated as the command"
    (is (nil? (dep/sh! "bb" "-e" "(System/exit 0)"))))

  (testing "first arg is a map: parsed as opts, even when empty"
    (is (nil? (dep/sh! {} "bb" "-e" "(System/exit 0)")))))

(deftest sh!-with-hints-captures-stderr-and-matches
  (testing "non-zero exit with a matching hint prints stderr and explanation"
    (let [printed (atom [])
          exits   (atom [])
          print!  (fn [x] (swap! printed conj x))
          exit!   (fn [n] (swap! exits conj n))]
      (dep/sh! {:hints    [{:match   #"boom-err"
                            :explain "this is the explanation"}]
                :print-fn print!
                :exit-fn  exit!}
               "bb" "-e"
               "(binding [*out* *err*] (println \"boom-err\") (System/exit 1))")
      (is (= [1] @exits) "exit code from the subprocess propagates")
      (is (some #(re-find #"boom-err" %) @printed)
          "captured stderr was replayed")
      (is (some #(re-find #"this is the explanation" %) @printed)
          "matching hint was printed"))))
