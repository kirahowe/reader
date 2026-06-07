(ns reader.handlers.queue
  (:require [integrant.core :as ig]
            [reader.reading :as reading]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.queue/archive [_ {:keys [datasource]}]
  (fn [req]
    (let [id (some-> (get-in req [:path-params :id]) parse-uuid)]
      ;; archive! is owner-scoped, so a forged id for another user's item (or a
      ;; missing one) returns nil — answered the same as a bad id: 404.
      (if (and id (reading/archive! datasource (:user-id req) id))
        (response/see-other "/")
        (response/not-found "No such queue item.")))))
