(ns reader.handlers.queue
  (:require [integrant.core :as ig]
            [reader.domain.reading :as reading]
            [reader.ui.pages.reader :as reader-page]
            [reader.web.datastar :as datastar]
            [reader.web.request :as request]
            [reader.web.response :as response]))

(defn- transition
  "A queue-item state-transition handler: parse the path id, apply the
   owner-scoped `transition!`, and answer with `(respond req row)`. A bad id (a
   non-uuid parses to nil) or another user's item — both yield nil from the
   mutator — answer 404 the same way."
  [datasource transition! respond]
  (fn [req]
    (let [id (request/path-uuid req)]
      (if-let [row (and id (transition! datasource (:user-id req) id))]
        (respond req row)
        (response/not-found "No such queue item.")))))

;; Archive removes the item from the list: a Datastar post (the queue row's
;; archive button) removes the row in place; a plain post returns home.
(defmethod ig/init-key :reader.handlers.queue/archive [_ {:keys [datasource]}]
  (transition datasource reading/archive!
              (fn [req row]
                (if (datastar/request? req)
                  (datastar/sse req
                                (fn [gen]
                                  (datastar/remove! gen (str "#q-" (:queue-items/id row)))))
                  (response/see-other "/")))))

;; Read/unread keep you on the reader view: a Datastar post patches the
;; controls in place (the state flip is instant), a plain post reloads them.
(defn- toggled [req row]
  (if (datastar/request? req)
    (datastar/patch req (reader-page/controls row))
    (response/see-other (str "/queue/" (:queue-items/id row)))))

(defmethod ig/init-key :reader.handlers.queue/mark-read [_ {:keys [datasource]}]
  (transition datasource reading/mark-read! toggled))

(defmethod ig/init-key :reader.handlers.queue/mark-unread [_ {:keys [datasource]}]
  (transition datasource reading/mark-unread! toggled))
