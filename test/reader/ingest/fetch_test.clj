(ns reader.ingest.fetch-test
  "Tests for the SSRF guard. The address classifier and url allow-check are the
   security-critical, pure parts and need no network: InetAddress/getByName on a
   literal IP just parses it, it does not resolve DNS.

   The redirect/status/content-type logic of `fetch` itself is exercised by
   injecting `:request-fn` (so no live server, which the SSRF guard would refuse
   on loopback anyway). Those tests address every hop by a literal *public* IP,
   so the per-hop `url-allowed?` re-guard passes without DNS."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.ingest.fetch :as fetch])
  (:import [java.net InetAddress]))

(defn- inet [s] (InetAddress/getByName s))

(deftest private-inet?-test
  (testing "loopback, private, link-local, and unique-local addresses are blocked"
    (is (fetch/private-inet? (inet "127.0.0.1")))
    (is (fetch/private-inet? (inet "10.0.0.5")))
    (is (fetch/private-inet? (inet "172.16.4.4")))
    (is (fetch/private-inet? (inet "192.168.1.1")))
    (is (fetch/private-inet? (inet "169.254.169.254")) "the cloud metadata address")
    (is (fetch/private-inet? (inet "0.0.0.0")))
    (is (fetch/private-inet? (inet "::1")))
    (is (fetch/private-inet? (inet "fd00::1")) "IPv6 unique-local"))
  (testing "public addresses are allowed"
    (is (not (fetch/private-inet? (inet "8.8.8.8"))))
    (is (not (fetch/private-inet? (inet "93.184.216.34"))))
    (is (not (fetch/private-inet? (inet "2606:2800:220:1:248:1893:25c8:1946"))))))

(deftest url-allowed?-test
  (testing "non-http(s) schemes are rejected"
    (is (not (fetch/url-allowed? "ftp://example.com/x")))
    (is (not (fetch/url-allowed? "file:///etc/passwd")))
    (is (not (fetch/url-allowed? "javascript:alert(1)")))
    (is (not (fetch/url-allowed? "not a url"))))
  (testing "http(s) URLs pointing at private/loopback hosts are rejected"
    (is (not (fetch/url-allowed? "http://127.0.0.1/admin")))
    (is (not (fetch/url-allowed? "http://localhost/")))
    (is (not (fetch/url-allowed? "https://10.0.0.1/")))
    (is (not (fetch/url-allowed? "http://169.254.169.254/latest/meta-data/")))
    (is (not (fetch/url-allowed? "http://[::1]/")))))

(deftest private-inet?-extended-test
  (testing "carrier-grade NAT (100.64.0.0/10) is blocked; just outside it is allowed"
    (is (fetch/private-inet? (inet "100.64.0.1")))
    (is (fetch/private-inet? (inet "100.127.255.255")))
    (is (not (fetch/private-inet? (inet "100.128.0.1"))))
    (is (not (fetch/private-inet? (inet "100.63.255.255")))))
  (testing "a private IPv4 embedded in NAT64/6to4 is blocked; an embedded public one is allowed"
    (is (fetch/private-inet? (inet "64:ff9b::192.168.1.1")) "NAT64 wrapping RFC1918")
    (is (fetch/private-inet? (inet "2002:0a00:0001::")) "6to4 wrapping 10.0.0.1")
    (is (not (fetch/private-inet? (inet "64:ff9b::8.8.8.8"))) "NAT64 wrapping a public v4")))

(deftest resolve-redirect-test
  (testing "relative and absolute redirects resolve against the base"
    (is (= "http://x.test/b?y=1" (#'fetch/resolve-redirect "http://x.test/a" "/b?y=1")))
    (is (= "https://y.test/c" (#'fetch/resolve-redirect "http://x.test/a" "https://y.test/c"))))
  (testing "a malformed Location surfaces as a classified fatal error, not a raw exception"
    (let [e (try (#'fetch/resolve-redirect "http://x.test/a" "http://bad host/x")
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :bad-redirect (:error-class (ex-data e))))
      (is (:fatal? (ex-data e))))))

(deftest validated-address-test
  (testing "a private/loopback target is rejected at connect time (the SSRF pin)"
    (let [e (try (#'fetch/validated-address (java.net.URI. "http://127.0.0.1/x"))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :blocked-url (:error-class (ex-data e))))
      (is (:fatal? (ex-data e)))))
  (testing "a public literal target yields a pinned socket address with the scheme's default port"
    (let [^java.net.InetSocketAddress sa (#'fetch/validated-address (java.net.URI. "https://93.184.216.34/x"))]
      (is (= "93.184.216.34" (.getHostAddress (.getAddress sa))))
      (is (= 443 (.getPort sa))))))

(deftest pinned-client-builds-test
  (testing "the SSRF-pinned client constructs without error (address-finder + SNI wired)"
    (is (some? @#'fetch/pinned-client))))

;; ── fetch redirect / status / content-type handling ──────────────────────
;;
;; `:request-fn` stands in for the real HTTP call so we drive fetch's response
;; handling offline. Every url is a literal public IP, so the per-hop SSRF
;; re-guard (url-allowed?) passes without DNS while still exercising for real
;; (a private redirect target is the one case where it must, and does, refuse).

(def ^:private pub "http://93.184.216.34")

(defn- err-data [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest fetch-returns-the-page-test
  (testing "a 200 HTML response yields the body, final url, status, and content-type"
    (let [resp (fetch/fetch (str pub "/a")
                            {:request-fn (fn [_] {:status  200
                                                  :headers {"content-type" "text/html; charset=utf-8"}
                                                  :body    "<h1>hi</h1>"})})]
      (is (= (str pub "/a") (:final-url resp)))
      (is (= "<h1>hi</h1>" (:html resp)))
      (is (= 200 (:status resp)))
      (is (re-find #"text/html" (:content-type resp))))))

(deftest fetch-follows-redirects-test
  (testing "an absolute redirect is followed, and final-url reflects the destination"
    (let [reqs (fn [url] (if (str/ends-with? url "/start")
                           {:status 301 :headers {"location" (str pub "/dest")}}
                           {:status 200 :headers {"content-type" "text/html"} :body "<p>arrived</p>"}))
          resp (fetch/fetch (str pub "/start") {:request-fn reqs})]
      (is (= (str pub "/dest") (:final-url resp)))
      (is (= "<p>arrived</p>" (:html resp)))))
  (testing "a relative Location resolves against the current url"
    (let [reqs (fn [url] (if (str/ends-with? url "/a")
                           {:status 302 :headers {"location" "/b"}}
                           {:status 200 :headers {"content-type" "text/html"} :body "ok"}))]
      (is (= (str pub "/b") (:final-url (fetch/fetch (str pub "/a") {:request-fn reqs})))))))

(deftest fetch-reguards-each-redirect-hop-test
  (testing "a redirect aimed at a loopback host is refused at the next hop (the SSRF re-guard)"
    (let [reqs (fn [_] {:status 302 :headers {"location" "http://127.0.0.1/admin"}})
          data (err-data #(fetch/fetch (str pub "/open-redirect") {:request-fn reqs}))]
      (is (= :blocked-url (:error-class data)))
      (is (:fatal? data)))))

(deftest fetch-caps-redirects-test
  (testing "an endless redirect chain is cut off as a fatal too-many-redirects"
    (let [n    (atom 0)
          reqs (fn [_] (swap! n inc) {:status 302 :headers {"location" (str pub "/r" @n)}})
          data (err-data #(fetch/fetch (str pub "/r0") {:request-fn reqs}))]
      (is (= :too-many-redirects (:error-class data)))
      (is (:fatal? data)))))

(deftest fetch-status-errors-test
  (testing "a 4xx is a fatal http-status error — retrying won't help"
    (let [data (err-data #(fetch/fetch (str pub "/missing")
                                       {:request-fn (fn [_] {:status 404 :headers {}})}))]
      (is (= :http-status (:error-class data)))
      (is (= 404 (:status data)))
      (is (:fatal? data))))
  (testing "a 5xx is a non-fatal http-status error — left retryable"
    (let [data (err-data #(fetch/fetch (str pub "/down")
                                       {:request-fn (fn [_] {:status 503 :headers {}})}))]
      (is (= :http-status (:error-class data)))
      (is (not (:fatal? data))))))

(deftest fetch-rejects-non-html-test
  (testing "a non-HTML content-type is a fatal not-html error"
    (let [data (err-data #(fetch/fetch (str pub "/file.pdf")
                                       {:request-fn (fn [_] {:status  200
                                                             :headers {"content-type" "application/pdf"}
                                                             :body    "%PDF-1.7"})}))]
      (is (= :not-html (:error-class data)))
      (is (:fatal? data)))))

(deftest fetch-network-error-test
  (testing "a bare transport error surfaces as a retryable :network error"
    (let [data (err-data #(fetch/fetch (str pub "/x")
                                       {:request-fn (fn [_] {:error (java.net.ConnectException. "refused")})}))]
      (is (= :network (:error-class data)))
      (is (not (:fatal? data)))))
  (testing "a classified error from the pinned client keeps its class and fatal flag"
    (let [boom (ex-info "blocked at connect" {:error-class :blocked-url :fatal? true})
          data (err-data #(fetch/fetch (str pub "/x") {:request-fn (fn [_] {:error boom})}))]
      (is (= :blocked-url (:error-class data)))
      (is (:fatal? data)))))
