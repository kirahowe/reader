(ns reader.handlers.tags
  "Per-user tag overrides on a queue item: add a tag, or remove one (suppressing a
   baseline tag, or dropping a prior add). Owner-scoped — a forged or foreign id
   404s, never touching another user's queue. Lean glue; the logic lives in
   reader.domain.tags."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [reader.domain.reading :as reading]
            [reader.domain.tags :as tags]
            [reader.web.request :as request]
            [reader.web.response :as response]))

(defn- owned [datasource req]
  (some->> (request/path-uuid req)
           (reading/owned-item datasource (:user-id req))))

(defmethod ig/init-key :reader.handlers.tags/add [_ {:keys [datasource]}]
  (fn [req]
    (let [qi    (owned datasource req)
          label (some-> (get-in req [:params "label"]) str/trim not-empty)]
      (if (and qi label)
        (let [tag (tags/find-or-create-label! datasource label)]
          (tags/add-tag! datasource (:queue-items/id qi) (:queue-items/readable-type qi)
                         (:queue-items/readable-id qi) (:id tag))
          (response/see-other (str "/queue/" (:queue-items/id qi))))
        (response/not-found "No such queue item.")))))

(defmethod ig/init-key :reader.handlers.tags/remove [_ {:keys [datasource]}]
  (fn [req]
    (let [qi     (owned datasource req)
          tag-id (some-> (get-in req [:path-params :tag-id]) parse-uuid)]
      (if (and qi tag-id)
        (do (tags/remove-tag! datasource (:queue-items/id qi) (:queue-items/readable-type qi)
                              (:queue-items/readable-id qi) tag-id)
            (response/see-other (str "/queue/" (:queue-items/id qi))))
        (response/not-found "No such queue item.")))))
