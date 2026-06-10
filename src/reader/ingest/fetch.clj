(ns reader.ingest.fetch
  "The HTTP fetch edge for ingest. Uses http-kit's bundled client (no new
   dependency). This is a trust boundary: the URL is user-supplied, so the
   fetch is SSRF-guarded — only http(s), and every hop (including redirects)
   must resolve to a public address, never loopback/private/link-local. Size,
   redirect count, content-type, and timeout are all capped.

   Expected failures throw `ex-info` carrying an `:error-class` keyword so the
   worker can record *why* an extraction failed; the success path returns
   `{:final-url :html :status :content-type}`."
  (:require [clojure.string :as str]
            [org.httpkit.client :as http])
  (:import [java.net InetAddress InetSocketAddress URI]))

(def ^:private max-bytes (* 5 1024 1024))
(def ^:private max-redirects 5)
(def ^:private timeout-ms 10000)
(def ^:private user-agent "ReaderBot/0.1 (+https://reader.kira.is)")

(defn- unique-local-ipv6?
  "fc00::/7 — IPv6 unique-local, which InetAddress doesn't flag as site-local."
  [^InetAddress a]
  (let [b (.getAddress a)]
    (and (= 16 (alength b))
         (= 0xfc (bit-and (bit-and (aget b 0) 0xff) 0xfe)))))

(defn- ipv4-octets
  "The four IPv4 octets (0-255) this address ultimately denotes — a plain IPv4,
   an IPv4-mapped IPv6 (::ffff:a.b.c.d), a NAT64 (64:ff9b::/96), or a 6to4
   (2002::/16) embedding — or nil for a genuine IPv6. Lets the v4 checks see
   through IPv6 wrappers that the InetAddress category predicates don't decode."
  [^InetAddress a]
  (let [b (mapv #(bit-and % 0xff) (.getAddress a))]
    (cond
      (= 4 (count b)) b
      (= 16 (count b))
      (cond
        (and (every? zero? (subvec b 0 10)) (= 0xff (b 10)) (= 0xff (b 11))) (subvec b 12 16)
        (and (= 0x00 (b 0)) (= 0x64 (b 1)) (= 0xff (b 2)) (= 0x9b (b 3))
             (every? zero? (subvec b 4 12)))                                 (subvec b 12 16)
        (and (= 0x20 (b 0)) (= 0x02 (b 1)))                                  (subvec b 2 6)
        :else nil))))

(defn- reserved-ipv4?
  "Private/reserved IPv4 ranges, including ones the InetAddress category
   predicates don't apply to a v4 embedded in IPv6 (NAT64/6to4): this-network
   (0/8), RFC1918, loopback, CGNAT (100.64.0.0/10), and link-local."
  [[a b _ _]]
  (or (= a 0) (= a 10) (= a 127)
      (and (= a 100) (<= 64 b 127))
      (and (= a 169) (= b 254))
      (and (= a 172) (<= 16 b 31))
      (and (= a 192) (= b 168))))

(defn private-inet?
  "Is `a` an address we must never fetch from (the SSRF blocklist)?"
  [^InetAddress a]
  (or (.isLoopbackAddress a)
      (.isAnyLocalAddress a)
      (.isLinkLocalAddress a)
      (.isSiteLocalAddress a)
      (.isMulticastAddress a)
      (unique-local-ipv6? a)
      (boolean (some-> (ipv4-octets a) reserved-ipv4?))))

(defn url-allowed?
  "True iff `url` is an http(s) URL whose host resolves only to public
   addresses. The SSRF gate, re-checked on every redirect hop."
  [url]
  (try
    (let [uri    (URI. url)
          scheme (some-> (.getScheme uri) str/lower-case)
          host   (some-> (.getHost uri) (str/replace #"^\[|\]$" ""))]
      (boolean
       (and (contains? #{"http" "https"} scheme)
            (not (str/blank? host))
            (let [addrs (seq (InetAddress/getAllByName host))]
              (and addrs (not-any? private-inet? addrs))))))
    (catch Exception _ false)))

;; ── SSRF-pinned http client ──────────────────────────────────────────────
;;
;; A custom http-kit client whose AddressFinder resolves the host and rejects
;; private addresses, then hands the *validated* InetSocketAddress to the
;; connection — so the IP that was checked is the IP we connect to, closing the
;; DNS-rebinding/TOCTOU window. We reuse http-kit's own SNI ssl-configurer so
;; HTTPS still verifies the certificate against the hostname (not the pinned IP).

(def ^:private sni-ssl-configurer
  (try (some-> (requiring-resolve 'org.httpkit.sni-client/ssl-configurer) deref)
       (catch Throwable _ nil)))

(defn- validated-address
  "AddressFinder for the pinned client: resolve `uri`'s host, reject if any
   resolved address is private, and return the InetSocketAddress to connect to.
   The resolution that validates is the one used to connect — no rebinding gap."
  [^URI uri]
  (let [scheme (some-> (.getScheme uri) str/lower-case)
        host   (.getHost uri)
        port   (let [p (.getPort uri)] (if (= -1 p) (if (= "https" scheme) 443 80) p))
        addrs  (InetAddress/getAllByName host)]
    (when (some private-inet? addrs)
      (throw (ex-info "blocked private address"
                      {:error-class :blocked-url :fatal? true :url (str uri)})))
    (InetSocketAddress. ^InetAddress (first addrs) (int port))))

(def ^:private pinned-client
  (delay (http/make-client (cond-> {:address-finder validated-address}
                             sni-ssl-configurer (assoc :ssl-configurer sni-ssl-configurer)))))

(defn- norm-headers [headers]
  (into {} (map (fn [[k v]] [(keyword (str/lower-case (name k))) v])) headers))

(defn- html? [content-type]
  (or (nil? content-type)
      (boolean (re-find #"(?i)text/html|application/xhtml" content-type))))

(defn- resolve-redirect [base location]
  (try
    (str (.resolve (URI. base) location))
    (catch Exception _
      (throw (ex-info "invalid redirect location"
                      {:error-class :bad-redirect :location location :url base :fatal? true})))))

(defn- default-request
  "The real HTTP GET one redirect hop makes: the SSRF-pinned client (validated
   IP) plus a body-size filter that aborts before an oversized response is
   buffered into memory. Injected into `fetch` as `:request-fn` so the redirect/
   status/content-type logic is testable offline — a live server can't stand in
   here, since the SSRF guard refuses the loopback address it would bind."
  [url]
  @(http/request {:url              url
                  :method           :get
                  :client           @pinned-client
                  :filter           (http/max-body-filter max-bytes)
                  :timeout          timeout-ms
                  :follow-redirects false
                  :as               :text
                  :headers          {"User-Agent" user-agent
                                     "Accept"     "text/html,application/xhtml+xml,*/*"}}))

(defn fetch
  "GET `url`, following redirects manually (re-guarding each hop), and return
   {:final-url :html :status :content-type}. Throws ex-info {:error-class …} on
   a blocked url, network error, non-2xx status, non-HTML body, oversize body,
   or redirect loop. `:request-fn` (url -> an http-kit response map) is injected
   so tests drive this logic without a network; it defaults to the SSRF-pinned
   client."
  ([url] (fetch url nil))
  ([url {:keys [request-fn] :or {request-fn default-request}}]
   (loop [url url redirects 0]
     (when (> redirects max-redirects)
       (throw (ex-info "too many redirects" {:error-class :too-many-redirects :url url :fatal? true})))
     (when-not (url-allowed? url)
       (throw (ex-info "blocked or invalid url" {:error-class :blocked-url :url url :fatal? true})))
     (let [{:keys [status headers body error]} (request-fn url)
           headers (norm-headers headers)]
       (when error
         ;; A blocked-address throw from the pinned client carries its own
         ;; :error-class/:fatal?; anything else is a transient network error.
         (throw (ex-info "fetch error"
                         (merge {:error-class :network :url url}
                                (select-keys (ex-data error) [:error-class :fatal?]))
                         error)))
       (cond
         (and (<= 300 status 399) (:location headers))
         (recur (resolve-redirect url (:location headers)) (inc redirects))

         (not (<= 200 status 299))
         ;; 4xx won't succeed on retry; 5xx might, so leave those retryable.
         (throw (ex-info "http error" {:error-class :http-status :status status :url url :fatal? (< status 500)}))

         (not (html? (:content-type headers)))
         (throw (ex-info "not html" {:error-class :not-html :content-type (:content-type headers) :url url :fatal? true}))

         :else
         {:final-url url :html (or body "") :status status :content-type (:content-type headers)})))))
