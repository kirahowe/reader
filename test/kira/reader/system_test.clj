(ns kira.reader.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [kira.reader.system :as system]))

(deftest config-loads
  (testing "base + test overlay produce a valid integrant config"
    (let [cfg (system/config)]
      (is (map? cfg))
      (is (contains? cfg :kira.reader.log/publisher))
      (testing "test overlay disables the http server"
        (is (nil? (get cfg :kira.reader.http/server)))))))

(deftest log-component-starts-and-stops
  (testing "the log publisher boots in isolation"
    (let [cfg {:kira.reader.log/publisher {:type :console :pretty? false}}]
      (ig/load-namespaces cfg)
      (let [sys (ig/init cfg)]
        (try
          (is (some? (get sys :kira.reader.log/publisher)))
          (finally (ig/halt! sys)))))))
