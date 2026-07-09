(ns reader.handlers.tags
  "Per-user tag overrides on a queue item: add a tag, or remove one (suppressing a
   baseline tag, or dropping a prior add). Owner-scoped — a forged or foreign id
   404s, never touching another user's queue. Lean glue; the logic lives in
   reader.domain.tags.

   A Datastar post patches the reader view's #reader-tags section back over SSE
   (and clears the bound $tag signal after an add); a plain post redirects."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [reader.domain.reading :as reading]
            [reader.domain.tags :as tags]
            [reader.ui.pages.reader :as reader-page]
            [reader.web.datastar :as datastar]
            [reader.web.request :as request]
            [reader.web.response :as response]))

(defn- owned [datasource req]
  (some->> (request/path-uuid req)
           (reading/owned-item datasource (:user-id req))))

(defn- tags-response
  "Answer a tag mutation: patch the re-rendered #reader-tags section for
   Datastar (running `extra!` on the open stream first, e.g. to clear the add
   field), or redirect back to the reader view."
  ([req datasource qi] (tags-response req datasource qi nil))
  ([req datasource qi extra!]
   (if (datastar/request? req)
     (datastar/sse req
                   (fn [gen]
                     (when extra! (extra! gen))
                     (datastar/patch! gen (reader-page/tags-editor
                                           (:queue-items/id qi)
                                           (tags/effective-for-queue-item datasource qi)))))
     (response/see-other (str "/queue/" (:queue-items/id qi))))))

(defmethod ig/init-key :reader.handlers.tags/add [_ {:keys [datasource]}]
  (fn [req]
    (let [qi    (owned datasource req)
          label (some-> (get-in req [:params "label"]) str/trim not-empty)]
      (if (and qi label)
        (let [tag (tags/find-or-create-label! datasource label)]
          (tags/add-tag! datasource (:queue-items/id qi) (:queue-items/readable-type qi)
                         (:queue-items/readable-id qi) (:id tag))
          (tags-response req datasource qi (fn [gen] (datastar/signals! gen {:tag ""}))))
        (response/not-found "No such queue item.")))))

(defmethod ig/init-key :reader.handlers.tags/remove [_ {:keys [datasource]}]
  (fn [req]
    (let [qi     (owned datasource req)
          tag-id (some-> (get-in req [:path-params :tag-id]) parse-uuid)]
      (if (and qi tag-id)
        (do (tags/remove-tag! datasource (:queue-items/id qi) (:queue-items/readable-type qi)
                              (:queue-items/readable-id qi) tag-id)
            (tags-response req datasource qi))
        (response/not-found "No such queue item.")))))
