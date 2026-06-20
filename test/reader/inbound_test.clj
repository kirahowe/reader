(ns reader.inbound-test
  "Inbound-email liveness derivation. The :reader.inbound/active? component tells
   the settings page whether mail to an alias actually gets delivered, derived
   from config (HMAC secret + storage) rather than a hand-set flag."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [reader.storage :as storage]))

(defn- active?
  "Build the :reader.inbound/active? component with `cfg` and return its value."
  [cfg]
  (let [config {:reader.inbound/active? cfg}]
    (ig/load-namespaces config)
    (let [sys (ig/init config)]
      (try (:reader.inbound/active? sys) (finally (ig/halt! sys))))))

(deftest active?-derives-liveness-from-config
  (let [ok      (storage/memory-store)
        missing (storage/disabled-store :r2-unconfigured)]
    (testing "live only when the HMAC secret is present AND storage is configured"
      (is (true? (active? {:hmac-secret "s3cr3t" :storage ok}))))
    (testing "inert when the secret is absent (dev/test, or prod before secrets land)"
      (is (false? (active? {:hmac-secret nil :storage ok})))
      (is (false? (active? {:hmac-secret "  " :storage ok}))))
    (testing "inert when storage isn't configured (half-configured prod)"
      (is (false? (active? {:hmac-secret "s3cr3t" :storage missing})))
      (is (false? (active? {:hmac-secret "s3cr3t" :storage nil}))))))
