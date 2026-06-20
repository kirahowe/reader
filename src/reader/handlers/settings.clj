(ns reader.handlers.settings
  "The settings page handlers. Lean glue: show the signed-in user's inbound alias,
   and rotate it (retire the old, mint a new) behind a type-to-confirm check.
   `inbound-domain` (where aliases are minted) and `active?` (whether inbound email
   is actually wired) are supplied per-environment."
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [reader.domain.inboxes :as inboxes]
            [reader.ui.pages.settings :as pages]
            [reader.web.response :as response]))

(defn- page [req inbox active? typed error]
  (pages/render {:user (:user req) :inbox inbox :inbound-active? active? :typed typed :error error}))

(defmethod ig/init-key :reader.handlers.settings/show [_ {:keys [datasource inbound-domain active?]}]
  (fn [req]
    (let [inbox (inboxes/find-or-provision! datasource (:user-id req) inbound-domain)]
      (response/html (page req inbox active? nil nil)))))

(defmethod ig/init-key :reader.handlers.settings/rotate [_ {:keys [datasource inbound-domain active?]}]
  (fn [req]
    (let [user-id (:user-id req)
          typed   (some-> (get-in req [:params "confirm"]) str/trim)
          inbox   (inboxes/current datasource user-id)]
      (if (and inbox (= typed (:email-inboxes/alias inbox)))
        (do (inboxes/rotate! datasource user-id inbound-domain)
            (response/see-other "/settings"))
        ;; No match — change nothing, re-render with an error. Fall back to
        ;; provisioning only if the user somehow has no alias yet to show.
        (response/html
         422
         (page req
               (or inbox (inboxes/find-or-provision! datasource user-id inbound-domain))
               active?
               typed
               "That didn’t match your current address — nothing was changed."))))))
