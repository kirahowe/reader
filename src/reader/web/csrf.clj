(ns reader.web.csrf
  "Origin-based CSRF defense for the cookie session. A cross-site browser write
   always carries an `Origin` (or `Referer`) that is some other site, so we
   reject unsafe-method requests whose origin isn't ours. Requests with neither
   header (curl, the test harness, non-browser clients) are not cross-site
   browser requests and pass through. The gate is inert when no site-origin is
   configured."
  (:require [clojure.string :as str]
            [integrant.core :as ig]))

(def ^:private safe-methods #{:get :head :options})

(defn- normalize-origin
  "Trim a configured site-origin and drop any trailing slash(es), so a config
   value like \"https://reader.test/\" still matches a browser Origin header
   (which never carries a trailing slash). nil/blank -> nil."
  [s]
  (some-> s str/trim not-empty (str/replace #"/+$" "")))

(defn- url-origin
  "The scheme://host[:port] origin of an absolute URL (e.g. a Referer), or nil if
   it can't be parsed. Comparing parsed origins — rather than a string prefix —
   accepts a bare-origin Referer (no path, as sent under `Referrer-Policy: origin`)
   while still rejecting a look-alike host like reader.test.evil.test."
  [url]
  (try
    (let [u      (java.net.URI. url)
          scheme (.getScheme u)
          host   (.getHost u)
          port   (.getPort u)]
      (when (and scheme host)
        (str scheme "://" host (when-not (neg? port) (str ":" port)))))
    (catch Exception _ nil)))

(defn- origin-ok? [req site-origin]
  (let [origin  (get-in req [:headers "origin"])
        referer (get-in req [:headers "referer"])]
    (cond
      origin  (= origin site-origin)
      referer (= (url-origin referer) site-origin)
      :else   true)))

(defn wrap-csrf [handler site-origin]
  (let [site-origin (normalize-origin site-origin)]
    (if-not site-origin
      handler
      (fn [req]
        (if (or (contains? safe-methods (:request-method req))
                (origin-ok? req site-origin))
          (handler req)
          {:status 403 :headers {"content-type" "text/plain"} :body "bad origin"})))))

(defmethod ig/init-key :reader.web/csrf [_ {:keys [site-origin]}]
  {:name ::csrf
   :wrap (fn [handler] (wrap-csrf handler site-origin))})
