(ns reader.ui.pages.login
  "The sign-in page. Hanko's <hanko-auth> web component (a JS island per
   principle 15) runs the passkey/passcode flow and sets the `hanko` session
   cookie; on session creation we redirect to the reading list."
  (:require [hiccup2.core :as h]
            [reader.ui.components :as c]
            [reader.ui.layout :as layout]))

(defn- hanko-island [api-url]
  ;; TODO: pin the hanko-elements version (esm.run/@teamhanko/hanko-elements@x.y.z)
  ;; and add SRI, or vendor the module under resources/public/js, before this grows
  ;; past an invite-only beta. The redirect uses the `hanko` client returned by
  ;; register() — its onSessionCreated, not a DOM event on the element.
  (h/raw
   (str "import { register } from "
        "'https://esm.run/@teamhanko/hanko-elements';\n"
        "const { hanko } = await register('" api-url "');\n"
        "hanko.onSessionCreated(() => { document.location.href = '/'; });\n")))

(defn render [api-url]
  (layout/page
   "Sign in"
   [:main.login-main
    [:div.login-card
     [:a.brand.brand-lg {:href "/"}
      [:span.brand-mark c/icon-book]
      [:span.brand-word "Reader"]]
     [:h1 "Sign in"]
     [:p.muted.login-tag "Articles, papers, and newsletters, in one queue."]
     [:hanko-auth]
     [:script {:type "module"} (hanko-island api-url)]]]))
