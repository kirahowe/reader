(ns reader.ui.pages.settings
  "The settings page: the signed-in identity, the user's inbound newsletter alias,
   and a control to rotate it. The alias is flagged as not-yet-active wherever
   inbound email isn't wired (dev, or a prod before its secrets land); rotating
   is gated behind retyping the current address, since it kills the old one."
  (:require [reader.ui.components :as c]
            [reader.ui.layout :as layout]))

(defn render [{:keys [user inbox inbound-active? typed error]}]
  (layout/app-page
   "Settings" :settings
   (list
    (c/page-head "Settings")
    (c/card
     [:h2 "Account"]
     [:p.muted "Signed in as " [:strong (:users/email user)]])
    (c/card
     [:h2 "Inbound newsletters"]
     [:p "Forward newsletters to your personal address and new issues land "
      "straight in your reading list:"]
     [:p.inbox-alias [:code (:email-inboxes/alias inbox)]]
     (when-not inbound-active?
       [:p.muted.inbox-pending
        "This address isn’t receiving mail yet — it becomes active once email "
        "routing is set up."])
     [:form.form.rotate-alias {:method "post" :action "/settings/rotate"}
      [:p.muted "Need a fresh address? Generating one immediately stops the "
       "current address from receiving mail. Retype it to confirm."]
      (c/text-field "Current address" "confirm" (or typed "") (when error [error]))
      (c/button {:type "submit"} "Generate a new address")]))))
