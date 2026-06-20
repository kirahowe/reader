(ns reader.handlers.settings
  "The settings page handler. Lean glue: provision-or-read the signed-in user's
   inbound alias, then render. `inbound-domain` is the configured inbound-email
   domain, supplied per-environment."
  (:require [integrant.core :as ig]
            [reader.domain.inboxes :as inboxes]
            [reader.ui.pages.settings :as pages]
            [reader.web.response :as response]))

(defmethod ig/init-key :reader.handlers.settings/show [_ {:keys [datasource inbound-domain]}]
  (fn [req]
    (let [inbox (inboxes/find-or-provision! datasource (:user-id req) inbound-domain)]
      (response/html (pages/render {:user (:user req) :inbox inbox})))))
