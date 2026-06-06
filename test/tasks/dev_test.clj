(ns tasks.dev-test
  "Tests for the `dev` bb-task orchestrator: the decision of whether to
   boot the system inside an already-running REPL or start a fresh dev
   JVM, exercised through injected seams (no socket, no JVM)."
  (:require [clojure.test :refer [deftest is testing]]
            [dev-task]))

(defn- seams []
  {:served (atom 0) :booted (atom 0) :says (atom [])})

(deftest dev!-starts-a-fresh-jvm-when-no-repl-is-advertised
  (testing "no .nrepl-port -> serve our own, never try to boot remotely"
    (let [{:keys [served booted says]} (seams)]
      (dev-task/dev! (constantly nil)
                     (fn [_] (swap! booted inc) {:value ":started" :err nil})
                     (fn [] (swap! served inc))
                     (fn [m] (swap! says conj m)))
      (is (= 1 @served))
      (is (zero? @booted) "must not dial a port that was never advertised"))))

(deftest dev!-boots-inside-a-live-repl-instead-of-starting-a-jvm
  (testing "a live .nrepl-port -> boot the system there, do not serve"
    (let [{:keys [served says]} (seams)]
      (dev-task/dev! (constantly 7888)
                     (fn [port]
                       (is (= 7888 port) "must dial the advertised port")
                       {:value ":started" :err nil})
                     (fn [] (swap! served inc))
                     (fn [m] (swap! says conj m)))
      (is (zero? @served) "an existing REPL means no second JVM")
      (is (some #(re-find #"7888" %) @says) "should report the port it used"))))

(deftest dev!-falls-back-to-a-jvm-on-a-stale-port-file
  (testing "a .nrepl-port whose port no longer listens -> start fresh"
    (let [{:keys [served says]} (seams)]
      (dev-task/dev! (constantly 9999)
                     (fn [_] (throw (java.net.ConnectException. "refused")))
                     (fn [] (swap! served inc))
                     (fn [m] (swap! says conj m)))
      (is (= 1 @served) "a dead advertised port must not block startup")
      (is (some #(re-find #"9999" %) @says) "should mention the stale port"))))

(deftest dev!-surfaces-a-boot-error-without-starting-a-second-jvm
  (testing "a live REPL missing the :dev alias -> actionable error, no serve"
    (let [{:keys [served says]} (seams)]
      (dev-task/dev! (constantly 7888)
                     (fn [_] {:value nil :err "Could not locate dev__init.class"})
                     (fn [] (swap! served inc))
                     (fn [m] (swap! says conj m)))
      (is (zero? @served) "the live JVM is alive; we must not race it with another")
      (is (some #(re-find #":dev" %) @says) "should point at the missing :dev alias"))))
