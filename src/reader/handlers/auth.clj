(ns reader.handlers.auth
  (:require [integrant.core :as ig]
            [reader.ui.pages.login :as login]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.auth/login [_ {:keys [api-url]}]
  (fn [_req]
    (response/html (login/render api-url))))

(defmethod ig/init-key :reader.handlers.auth/logout [_ _]
  (fn [_req]
    ;; Clear the session cookie locally and bounce to /login. The cookie is
    ;; what our app reads, so dropping it logs the user out here; revoking the
    ;; Hanko session itself can come later via the frontend SDK if needed.
    (response/expire-cookie (response/see-other "/login") "hanko")))
