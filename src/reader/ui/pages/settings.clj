(ns reader.ui.pages.settings
  "The settings page: the signed-in identity and the user's inbound newsletter
   alias. The alias is shown but flagged as not-yet-active until email routing
   is configured."
  (:require [reader.ui.components :as c]
            [reader.ui.layout :as layout]))

(defn render [{:keys [user inbox]}]
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
     [:p.muted.inbox-pending
      "This address isn’t receiving mail yet — it becomes active once email "
      "routing is set up."]))))
