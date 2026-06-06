(ns reader.handlers.readables
  (:require [integrant.core :as ig]
            [reader.db.crud :as crud]
            [reader.readables :as readables]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.readables/delete [_ {:keys [datasource]}]
  (fn [req]
    (let [table (keyword (get-in req [:path-params :table]))
          id    (some-> (get-in req [:path-params :id]) parse-uuid)]
      (if (and id (contains? readables/readable-tables table))
        (do (crud/delete! datasource table id)
            (response/see-other "/"))
        (response/not-found "No such readable.")))))
