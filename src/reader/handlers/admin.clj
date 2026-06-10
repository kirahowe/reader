(ns reader.handlers.admin
  "The eval dashboard handler — admin-gated. The signed-in user's email
   (attached by the auth middleware) must be in the configured `admin-emails`;
   anyone else gets a 404, so the route doesn't even confirm it exists."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [reader.admin :as admin]
            [reader.ui.pages.admin :as pages]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.admin/extractions [_ {:keys [datasource admin-emails]}]
  (let [admins (into #{} (map str/lower-case) admin-emails)]
    (fn [req]
      (if (contains? admins (some-> (:user req) :users/email str str/lower-case))
        (response/html (pages/render (admin/summary datasource)))
        (response/not-found "Not found.")))))
