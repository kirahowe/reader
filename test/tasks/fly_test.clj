(ns tasks.fly-test
  "Tests for the `fly` namespace — pure report builders, plus
   subprocess integration tests that invoke `bb` end-to-end to verify
   the dependency-check error UX stays clean (no stack traces, no
   `clojure.lang.ExceptionInfo`, no `NO_SOURCE_PATH:NN:NN`)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is testing]]
            [depends :as dep]
            [fly]))

(deftest default-app-name-reads-fly-toml
  (testing "returns the `app = \"...\"` value from the real fly.toml"
    (is (= "kirahowe-reader" (fly/default-app-name)))))

(deftest default-app-name-with-synthetic-path
  (testing "reads the app name from an arbitrary fly-toml-shaped file"
    (let [tmp (fs/create-temp-dir)
          toml (str (fs/path tmp "custom.toml"))]
      (try
        (spit toml "app = \"some-other-name\"\nprimary_region = \"ord\"\n")
        (is (= "some-other-name" (fly/default-app-name toml)))
        (finally (fs/delete-tree tmp))))))

(deftest default-app-name-returns-nil-when-no-app-key
  (testing "a fly-toml-shaped file without an `app =` line yields nil"
    (let [tmp (fs/create-temp-dir)
          toml (str (fs/path tmp "noapp.toml"))]
      (try
        (spit toml "primary_region = \"ord\"\n")
        (is (nil? (fly/default-app-name toml)))
        (finally (fs/delete-tree tmp))))))

(deftest not-authed-builder
  (testing "ok on a successful auth check"
    (is (= dep/ok (fly/not-authed {:exit 0}))))

  (testing "produces an actionable failure report when not authed"
    (let [r (fly/not-authed {:exit 1})]
      (is (= 1 (:exit r)))
      (is (some #(re-find #"Not authenticated with Fly\.io" %) (:lines r)))
      (is (some #(re-find #"flyctl auth login" %) (:lines r))))))

(deftest app-missing-builder
  (testing "ok when the app is present"
    (is (= dep/ok (fly/app-missing {:exit 0} "anything" "hint"))))

  (testing "produces an actionable failure report when the app is missing"
    (let [r (fly/app-missing {:exit 1} "my-app" "Run `bb fly:init`")]
      (is (= 1 (:exit r)))
      (is (some #(re-find #"my-app.*not found" %) (:lines r)))
      (is (some #(re-find #"bb fly:init" %) (:lines r))))))

(deftest init-hints-shape
  (testing "every hint has a Pattern :match and a non-empty :explain"
    (doseq [{:keys [match explain]} fly/init-hints]
      (is (instance? java.util.regex.Pattern match))
      (is (string? explain))
      (is (seq explain)))))

(deftest init-hints-known-collisions
  (testing "the 'name taken' error is covered"
    (let [explained? (some (fn [{:keys [match]}]
                             (re-find match "Error: Validation failed: Name has already been taken"))
                           fly/init-hints)]
      (is explained? "init-hints should match Fly's globally-taken-name error"))))

(deftest message-builders-shape
  (testing "every builder returns :exit 0 so dep chains keep flowing"
    (is (= 0 (:exit (fly/creating "x" "y"))))
    (is (= 0 (:exit (fly/already-exists "x"))))
    (is (= 0 (:exit fly/done))))

  (testing "creating message references the app and org"
    (let [{:keys [lines]} (fly/creating "my-app" "my-org")]
      (is (some #(re-find #"my-app" %) lines))
      (is (some #(re-find #"my-org" %) lines))))

  (testing "already-exists mentions the app"
    (let [{:keys [lines]} (fly/already-exists "my-app")]
      (is (some #(re-find #"my-app" %) lines))))

  (testing "done has a finished message"
    (is (seq (:lines fly/done)))))

(deftest init!-when-app-already-exists
  (testing "no-op path: says `already-exists` and never shells out"
    (let [says     (atom [])
          shells   (atom [])
          say-fn   (fn [r] (swap! says conj r))
          sh-fn    (fn [& args] (swap! shells conj (vec args)))
          status-fn (fn [& _] {:exit 0})]
      (fly/init! (constantly "the-app") status-fn say-fn sh-fn "personal")
      (is (= [(fly/already-exists "the-app")] @says))
      (is (= [] @shells)))))

(deftest init!-when-app-does-not-exist
  (testing "create path: announces, runs flyctl, then announces done"
    (let [says       (atom [])
          shells     (atom [])
          say-fn     (fn [r] (swap! says conj r))
          sh-fn      (fn [& args] (swap! shells conj (vec args)))
          status-fn  (fn [& _] {:exit 1})]
      (fly/init! (constantly "new-app") status-fn say-fn sh-fn "my-org")
      (is (= [(fly/creating "new-app" "my-org") fly/done] @says))
      (is (= [[{:hints fly/init-hints}
               "flyctl" "apps" "create" "new-app" "--org" "my-org"]]
             @shells)))))

(deftest require-authed!-success
  (testing "ok is forwarded to say-fn when whoami succeeds"
    (let [says (atom [])
          say-fn (fn [r] (swap! says conj r))]
      (fly/require-authed! (constantly {:exit 0}) say-fn)
      (is (= [dep/ok] @says)))))

(deftest require-authed!-failure
  (testing "failure report mentions auth and the fix command"
    (let [says (atom [])
          say-fn (fn [r] (swap! says conj r))]
      (fly/require-authed! (constantly {:exit 1}) say-fn)
      (is (= 1 (count @says)))
      (let [r (first @says)]
        (is (= 1 (:exit r)))
        (is (some #(re-find #"Not authenticated" %) (:lines r)))
        (is (some #(re-find #"flyctl auth login" %) (:lines r)))))))

(deftest require-app!-success
  (testing "ok is forwarded to say-fn when flyctl status succeeds"
    (let [says (atom [])
          say-fn (fn [r] (swap! says conj r))]
      (fly/require-app! "my-app" "do the hint"
                        (constantly {:exit 0}) say-fn)
      (is (= [dep/ok] @says)))))

(deftest require-app!-failure
  (testing "failure report mentions the app name and hint"
    (let [says (atom [])
          say-fn (fn [r] (swap! says conj r))]
      (fly/require-app! "missing-app" "Run `bb fly:init`"
                        (constantly {:exit 1}) say-fn)
      (is (= 1 (count @says)))
      (let [r (first @says)]
        (is (= 1 (:exit r)))
        (is (some #(re-find #"missing-app.*not found" %) (:lines r)))
        (is (some #(re-find #"bb fly:init" %) (:lines r)))))))

;; --- Subprocess integration tests --------------------------------------------
;; These invoke `bb` with a clean PATH that hides specific tools, asserting
;; that the corresponding dependency check produces a clean exit-1 failure
;; (and not a potentially confusing babashka.process stack trace).

(defn- bb-on-path []
  (or (some-> (fs/which "bb") str)
      (throw (ex-info "bb not found on PATH; integration tests need it" {}))))

(defn- run-bb-with-isolated-path
  "Spawn `bb` with PATH set to a temp dir containing only a symlink to
   `bb` itself, so any other CLI (flyctl, docker, etc.) is unreachable.
   Returns {:exit :out :err}."
  [& bb-args]
  (let [tmp (fs/create-temp-dir)]
    (try
      (fs/create-sym-link (fs/path tmp "bb") (bb-on-path))
      (let [result (apply p/shell
                          {:out :string :err :string :continue true
                           :extra-env {"PATH" (str tmp ":/usr/bin")}}
                          "bb" bb-args)]
        (select-keys result [:exit :out :err]))
      (finally (fs/delete-tree tmp)))))

(defn- combined [{:keys [out err]}] (str out err))

(defn- no-stack-trace? [output]
  (and (not (re-find #"clojure\.lang\.ExceptionInfo" output))
       (not (re-find #"NO_SOURCE_PATH" output))
       (not (re-find #"babashka\.process/error" output))))

(deftest no-stack-trace?-recognises-clean-output
  (testing "a tidy actionable error message is treated as clean"
    (is (no-stack-trace?
         "flyctl is not installed\n  → Install: https://fly.io/docs/flyctl/install/"))))

(deftest no-stack-trace?-rejects-known-stack-trace-patterns
  (testing "clojure.lang.ExceptionInfo trips the predicate"
    (is (not (no-stack-trace?
              "foo\n  at clojure.lang.ExceptionInfo (...)\nbar"))))

  (testing "NO_SOURCE_PATH location markers trip the predicate"
    (is (not (no-stack-trace?
              "Error at NO_SOURCE_PATH:14:7"))))

  (testing "babashka.process/error trips the predicate"
    (is (not (no-stack-trace?
              "  at babashka.process/error (process.clj:99)")))))

(deftest bb-deploy-fails-cleanly-when-flyctl-missing
  (testing "bb deploy with no flyctl on PATH exits 1 with an actionable hint"
    (let [result (run-bb-with-isolated-path "deploy")]
      (is (= 1 (:exit result)))
      (is (re-find #"flyctl is not installed" (combined result)))
      (is (re-find #"fly\.io/docs/flyctl/install" (combined result)))
      (is (no-stack-trace? (combined result))))))

(deftest bb-image-fails-cleanly-when-docker-missing
  (testing "bb image with no docker on PATH exits 1 with an actionable hint"
    (let [result (run-bb-with-isolated-path "image")]
      (is (= 1 (:exit result)))
      (is (re-find #"docker is not installed" (combined result)))
      (is (re-find #"docs\.docker\.com" (combined result)))
      (is (no-stack-trace? (combined result))))))

(deftest bb-tasks-listing-hides-private-tasks
  (testing "preflight tasks (no :doc, :private true) don't appear in `bb tasks`"
    (is (fs/exists? "bb.edn") "tests must run from project root")
    (let [result (apply p/shell {:out :string :err :string :continue true}
                        ["bb" "tasks"])
          listing (str (:out result) (:err result))]
      (is (zero? (:exit result)))
      (is (re-find #"(?m)^deploy\b"   listing))
      (is (re-find #"(?m)^fly:init\b" listing))
      (is (not (re-find #"flyctl:installed" listing)))
      (is (not (re-find #"flyctl:authed"    listing)))
      (is (not (re-find #"fly:app-exists"   listing)))
      (is (not (re-find #"docker:installed" listing))))))
