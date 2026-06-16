(ns reader.web.middleware
  "The cross-cutting ring middleware that needs no per-environment config:
   request logging, a catch-all exception gate, and query/form parameter
   parsing. Each is an Integrant component returning a reitit middleware, so the
   full stack — these plus the stateful CSRF and auth components — is assembled
   in declaration order in the system config, not in code."
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [reitit.ring.middleware.parameters :as parameters]
            [reader.web.response :as response]))

(defn wrap-request-log [handler]
  (fn [req]
    (let [start (System/nanoTime)
          resp  (handler req)
          ms    (quot (- (System/nanoTime) start) 1000000)]
      (log/info "request" {:method (:request-method req)
                           :uri    (:uri req)
                           :status (:status resp)
                           :ms     ms})
      resp)))

(defn wrap-exception [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (log/error t "unhandled exception" {:method (:request-method req)
                                            :uri    (:uri req)})
        (response/server-error)))))

(defmethod ig/init-key :reader.web.middleware/request-log [_ _]
  {:name ::request-log :wrap wrap-request-log})

(defmethod ig/init-key :reader.web.middleware/exception [_ _]
  {:name ::exception :wrap wrap-exception})

(defmethod ig/init-key :reader.web.middleware/parameters [_ _]
  parameters/parameters-middleware)
