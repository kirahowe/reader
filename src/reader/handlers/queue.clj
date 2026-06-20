(ns reader.handlers.queue
  (:require [integrant.core :as ig]
            [reader.domain.reading :as reading]
            [reader.web.request :as request]
            [reader.web.response :as response]))

(defn- transition
  "A queue-item state-transition handler: parse the path id, apply the
   owner-scoped `transition!`, and 303 to `(redirect-to id)`. A bad id (a non-uuid
   parses to nil) or another user's item — both yield nil from the mutator — answer
   404 the same way."
  [datasource transition! redirect-to]
  (fn [req]
    (let [id (request/path-uuid req)]
      (if (and id (transition! datasource (:user-id req) id))
        (response/see-other (redirect-to id))
        (response/not-found "No such queue item.")))))

;; Archive removes the item from the list, so it returns home; read/unread keep
;; you on the reader view to see the state flip.
(defmethod ig/init-key :reader.handlers.queue/archive [_ {:keys [datasource]}]
  (transition datasource reading/archive! (constantly "/")))

(defmethod ig/init-key :reader.handlers.queue/mark-read [_ {:keys [datasource]}]
  (transition datasource reading/mark-read! #(str "/queue/" %)))

(defmethod ig/init-key :reader.handlers.queue/mark-unread [_ {:keys [datasource]}]
  (transition datasource reading/mark-unread! #(str "/queue/" %)))
