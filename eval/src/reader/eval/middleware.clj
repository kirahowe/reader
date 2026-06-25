(ns reader.eval.middleware
  "The evals app is operator-only. The reused reader auth middleware verifies the
   Hanko session and attaches :user; this gate then admits only configured
   operators. It is necessary, not redundant: auth lets in any user already in the
   reader's `users` table, so without this gate every reader user could reach the
   dashboards. A non-operator gets 404, so the routes don't even confirm they
   exist — the same no-existence-leak posture as reader.handlers.admin."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [reader.web.response :as response]))

(defn- public? [req]
  (boolean (-> req :reitit.core/match :data :public?)))

(defn- operator? [ops req]
  (contains? ops (some-> req :user :users/email str str/lower-case)))

(defmethod ig/init-key :reader.eval.middleware/operator-gate [_ {:keys [operator-emails]}]
  (let [ops (into #{} (map str/lower-case) operator-emails)]
    {:name ::operator-gate
     :wrap (fn [handler]
             (fn [req]
               (if (or (public? req) (operator? ops req))
                 (handler req)
                 (response/not-found "Not found."))))}))
