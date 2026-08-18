(ns reader.ingest.email-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [reader.ingest.email :as email])
  (:import (java.time Instant)))

(def raw-eml
  (str/join "\r\n"
            ["From: Ben Thompson <ben@stratechery.com>"
             "To: r-abc@inbox.reader.test"
             "Subject: Weekly Update"
             "Message-ID: <issue-42@stratechery.com>"
             "Date: Mon, 8 Jun 2026 12:00:00 +0000"
             "List-Unsubscribe: <https://stratechery.com/unsub?u=42>, <mailto:unsub@stratechery.com>"
             "MIME-Version: 1.0"
             "Content-Type: multipart/alternative; boundary=\"BOUND\""
             ""
             "--BOUND"
             "Content-Type: text/plain; charset=UTF-8"
             ""
             "Plain text fallback."
             ""
             "--BOUND"
             "Content-Type: text/html; charset=UTF-8"
             ""
             "<html><body><h1>Hello</h1><p>World &amp; friends</p><script>alert('x')</script></body></html>"
             ""
             "--BOUND--"
             ""]))

(deftest parse-multipart-alternative
  (let [{:keys [subject from-name from-email sent-at message-id unsubscribe-url body-html]}
        (email/parse (.getBytes raw-eml "UTF-8"))]
    (testing "decodes the headers"
      (is (= "Weekly Update" subject))
      (is (= "Ben Thompson" from-name))
      (is (= "ben@stratechery.com" from-email))
      (is (= "<issue-42@stratechery.com>" message-id))
      (is (= (Instant/parse "2026-06-08T12:00:00Z") sent-at)))
    (testing "picks the https unsubscribe target from List-Unsubscribe (over the mailto)"
      (is (= "https://stratechery.com/unsub?u=42" unsubscribe-url)))
    (testing "prefers the html alternative and sanitizes it"
      (is (str/includes? body-html "<h2>Hello</h2>") "email-level h1 is demoted below the reader title")
      (is (str/includes? body-html "World"))
      (is (not (str/includes? body-html "alert")) "the script is stripped")
      (is (not (str/includes? body-html "<script"))))))

(deftest parse-plain-only-falls-back
  (let [raw (str/join "\r\n"
                      ["From: News <hello@example.com>"
                       "Subject: Plain"
                       "Message-ID: <p1@example.com>"
                       "Content-Type: text/plain; charset=UTF-8"
                       ""
                       "Just text <not a tag>."
                       ""])
        {:keys [body-html unsubscribe-url]} (email/parse (.getBytes raw "UTF-8"))]
    (testing "a text/plain-only email is wrapped and escaped"
      (is (str/includes? body-html "Just text"))
      (is (not (str/includes? body-html "<not a tag>")) "angle brackets escaped, not left as markup"))
    (testing "no List-Unsubscribe header -> nil"
      (is (nil? unsubscribe-url)))))

(deftest parse-unsubscribe-mailto-only
  (let [raw (str/join "\r\n"
                      ["From: News <hello@example.com>"
                       "Subject: Mailto only"
                       "Message-ID: <m1@example.com>"
                       "List-Unsubscribe: <mailto:leave@example.com?subject=unsub>"
                       "Content-Type: text/plain; charset=UTF-8"
                       ""
                       "Bye."
                       ""])
        {:keys [unsubscribe-url]} (email/parse (.getBytes raw "UTF-8"))]
    (testing "falls back to the mailto unsubscribe when no http(s) is offered"
      (is (= "mailto:leave@example.com?subject=unsub" unsubscribe-url)))))

(def gmail-forward-eml
  (str/join "\r\n"
            ["From: Alice Forwarder <alice@personal.test>"
             "To: r-abc@inbox.reader.test"
             "Subject: Fwd: The Systems Dispatch"
             "Message-ID: <delivery-99@personal.test>"
             "Date: Tue, 9 Jun 2026 15:00:00 +0000"
             "MIME-Version: 1.0"
             "Content-Type: text/html; charset=UTF-8"
             ""
             "<html><body>"
             "<p>You will love this.</p><p>-- Alice</p>"
             "<div class=\"gmail_quote\">"
             "<div class=\"gmail_attr\">---------- Forwarded message ---------<br>"
             "From: Dispatch Delivery &lt;newsletter@dispatch.example&gt;<br>"
             "Date: Mon, 8 Jun 2026 12:00:00 +0000<br>"
             "Subject: The Systems Dispatch<br>"
             "To: Alice Forwarder &lt;alice@personal.test&gt;</div>"
             "<html><head>"
             "<meta name=\"author\" content=\"By Priya Shah and Mateo Ruiz\">"
             "<meta property=\"og:site_name\" content=\"Systems Dispatch\">"
             "<link rel=\"canonical\" href=\"https://dispatch.example/issues/systems\">"
             "</head><body><h1>The Systems Dispatch</h1>"
             "<p class=\"byline\">By Priya Shah and Mateo Ruiz</p>"
             "<p id=\"newsletter-start\">The actual newsletter starts here.</p>"
             "<table width=\"640\"><tr><td width=\"640\">Wide layout</td></tr></table>"
             "<img src=\"https://dispatch.example/hero.jpg\" width=\"640\" height=\"320\">"
             "<img src=\"https://track.example/open.gif\" width=\"1\" height=\"1\">"
             "<a href=\"https://dispatch.example/read\">Read online</a>"
             "<script>alert('x')</script></body></html>"
             "</div></body></html>"
             ""]))

(deftest parse-gmail-forward
  (let [{:keys [delivery newsletter forward message-id subject from-name from-email
                sent-at body-html authors source forwarded?]}
        (email/parse (.getBytes gmail-forward-eml "UTF-8"))]
    (testing "keeps delivery identity separate from the embedded newsletter"
      (is (= "<delivery-99@personal.test>" message-id))
      (is (= "<delivery-99@personal.test>" (:message-id delivery)))
      (is (= "alice@personal.test" (:from-email delivery)))
      (is (true? forwarded?))
      (is (:detected? forward))
      (is (= :gmail (-> forward :hops first :client))))
    (testing "uses the original newsletter headers, not the forwarder"
      (is (= "The Systems Dispatch" subject))
      (is (= "Dispatch Delivery" from-name))
      (is (= "newsletter@dispatch.example" from-email))
      (is (= (Instant/parse "2026-06-08T12:00:00Z") sent-at))
      (is (= subject (:subject newsletter))))
    (testing "extracts newsletter bylines and publication metadata"
      (is (= ["Priya Shah" "Mateo Ruiz"] (mapv :name authors)))
      (is (= "Systems Dispatch" (:name source)))
      (is (= "https://dispatch.example" (:url source))))
    (testing "removes forwarding chrome and normalizes hostile email layout"
      (is (str/includes? body-html "actual newsletter starts here"))
      (is (not (str/includes? body-html "You will love this")))
      (is (not (str/includes? body-html "Alice Forwarder")))
      (is (not (str/includes? body-html "Forwarded message")))
      (is (not (str/includes? body-html "<h2>The Systems Dispatch</h2>"))
          "the reader title is not repeated inside the newsletter body")
      (is (not (str/includes? body-html "By Priya Shah and Mateo Ruiz"))
          "the promoted byline is not repeated inside the body")
      (is (not (str/includes? body-html "width=\"640\"")))
      (is (not (str/includes? body-html "track.example")))
      (is (not (str/includes? body-html "<script")))
      (is (str/includes? body-html "loading=\"lazy\""))
      (is (str/includes? body-html "referrerpolicy=\"no-referrer\""))
      (is (str/includes? body-html "rel=\"noopener noreferrer\"")))))

(deftest reply-quote-is-not-mistaken-for-a-forward
  (let [raw (str/join "\r\n"
                      ["From: Alice <alice@personal.test>"
                       "Subject: Re: A conversation"
                       "Message-ID: <reply-1@personal.test>"
                       "Content-Type: text/html; charset=UTF-8"
                       ""
                       "<p>My reply.</p><div class=\"gmail_quote\">On Monday, Bob wrote:<blockquote><p>From first principles.</p></blockquote></div>"
                       ""])
        {:keys [forwarded? subject body-html]} (email/parse (.getBytes raw "UTF-8"))]
    (is (false? forwarded?))
    (is (= "Re: A conversation" subject))
    (is (str/includes? body-html "My reply"))
    (is (str/includes? body-html "From first principles"))))

(deftest plain-forward-strips-the-envelope
  (let [raw (str/join "\r\n"
                      ["From: Alice <alice@personal.test>"
                       "Subject: Fwd: Plain Dispatch"
                       "Message-ID: <plain-forward@personal.test>"
                       "Content-Type: text/plain; charset=UTF-8"
                       ""
                       "A note from Alice."
                       ""
                       "---------- Forwarded message ---------"
                       "From: Jamie Writer <jamie@plain.example>"
                       "Date: Mon, 8 Jun 2026 12:00:00 +0000"
                       "Subject: Plain Dispatch"
                       "To: Alice <alice@personal.test>"
                       ""
                       "The plain newsletter body."
                       "Second line."
                       ""])
        {:keys [forwarded? subject from-name body-html]} (email/parse (.getBytes raw "UTF-8"))]
    (is (true? forwarded?))
    (is (= "Plain Dispatch" subject))
    (is (= "Jamie Writer" from-name))
    (is (str/includes? body-html "plain newsletter body"))
    (is (not (str/includes? body-html "A note from Alice")))
    (is (not (str/includes? body-html "Forwarded message")))))

(deftest html-attachment-is-not-promoted-to-body
  (let [raw (str/join "\r\n"
                      ["From: News <news@example.com>"
                       "Subject: Mixed"
                       "Message-ID: <mixed@example.com>"
                       "MIME-Version: 1.0"
                       "Content-Type: multipart/mixed; boundary=\"MIX\""
                       ""
                       "--MIX"
                       "Content-Type: text/html; charset=UTF-8"
                       "Content-Disposition: attachment; filename=\"other.html\""
                       ""
                       "<p>Attachment should not render.</p>"
                       "--MIX"
                       "Content-Type: text/html; charset=UTF-8"
                       ""
                       "<p>The actual message body.</p>"
                       "--MIX--"
                       ""])
        {:keys [body-html]} (email/parse (.getBytes raw "UTF-8"))]
    (is (str/includes? body-html "actual message body"))
    (is (not (str/includes? body-html "Attachment should not render")))))

(defn- client-forward-eml [subject body]
  (str/join "\r\n"
            ["From: Alice Forwarder <alice@personal.test>"
             (str "Subject: " subject)
             "Message-ID: <client-forward@personal.test>"
             "Content-Type: text/html; charset=UTF-8"
             ""
             body
             ""]))

(deftest recognizes-common-forward-client-structures
  (let [cases [{:client :apple-mail
                :html (str "<p>Outer note.</p><div>Begin forwarded message:</div>"
                           "<blockquote type=\"cite\"><div>From: Apple Author &lt;author@apple.example&gt;<br>"
                           "Date: Mon, 8 Jun 2026 12:00:00 +0000<br>Subject: Apple Issue<br>"
                           "To: Alice &lt;alice@personal.test&gt;</div><p>Apple newsletter body.</p></blockquote>")
                :needle "Apple newsletter body" :author "Apple Author"}
               {:client :outlook
                :html (str "<p>Outer note.</p><div id=\"divRplyFwdMsg\">From: Outlook Author &lt;author@outlook.example&gt;<br>"
                           "Sent: Mon, 8 Jun 2026 12:00:00 +0000<br>To: Alice<br>Subject: Outlook Issue</div>"
                           "<p>Outlook newsletter body.</p>")
                :needle "Outlook newsletter body" :author "Outlook Author"}
               {:client :yahoo
                :html (str "<p>Outer note.</p><div class=\"yahoo_quoted\"><div>----- Forwarded Message -----<br>"
                           "From: Yahoo Author &lt;author@yahoo.example&gt;<br>Subject: Yahoo Issue<br>"
                           "Date: Mon, 8 Jun 2026 12:00:00 +0000<br>To: Alice</div>"
                           "<p>Yahoo newsletter body.</p></div>")
                :needle "Yahoo newsletter body" :author "Yahoo Author"}
               {:client :thunderbird
                :html (str "<p>Outer note.</p><div class=\"moz-forward-container\">"
                           "<table class=\"moz-email-headers-table\"><tr><td>From: Thunderbird Author &lt;author@mozilla.example&gt;</td></tr>"
                           "<tr><td>Date: Mon, 8 Jun 2026 12:00:00 +0000</td></tr><tr><td>Subject: Thunderbird Issue</td></tr>"
                           "<tr><td>To: Alice</td></tr></table><p>Thunderbird newsletter body.</p></div>")
                :needle "Thunderbird newsletter body" :author "Thunderbird Author"}]]
    (doseq [{:keys [client html needle author]} cases]
      (testing (name client)
        (let [{:keys [forwarded? from-name body-html forward]}
              (email/parse (.getBytes (client-forward-eml "Fwd: Client Issue" html) "UTF-8"))]
          (is (true? forwarded?))
          (is (= client (-> forward :hops first :client)))
          (is (= author from-name))
          (is (str/includes? body-html needle))
          (is (not (str/includes? body-html "Outer note"))))))))

(deftest recognizes-realistic-apple-mail-split-header-rows
  ;; Redacted structural regression derived from a real Apple Mail .eml. Apple
  ;; emits the marker and every original header as separate blockquote children;
  ;; the earlier synthetic fixture put all headers in one div and missed this.
  (let [html (str "<div id=\"lineBreakAtBeginningOfMessage\"><br></div>"
                  "<p>Outer note.</p><blockquote type=\"cite\">"
                  "<div>Begin forwarded message:</div><br class=\"Apple-interchange-newline\">"
                  "<div><span><b>From:</b></span> <span>Real Author &lt;author@real.example&gt;<br></span></div>"
                  "<div><span><b>Subject:</b></span> <span><b>Real Issue</b><br></span></div>"
                  "<div><span><b>Date:</b></span> <span>Mon, 8 Jun 2026 12:00:00 +0000<br></span></div>"
                  "<div><span><b>To:</b></span> <span>Alice &lt;alice@personal.test&gt;<br></span></div>"
                  "<div><span><b>Reply-To:</b></span> <span>Replies &lt;reply@real.example&gt;<br></span></div>"
                  "<br><div><table><tbody><tr><td><p>The real newsletter body.</p></td></tr></tbody></table></div>"
                  "</blockquote>")
        {:keys [forwarded? subject from-name body-html forward]}
        (email/parse (.getBytes (client-forward-eml "Fwd: Real Issue" html) "UTF-8"))]
    (is (true? forwarded?))
    (is (= :apple-mail (-> forward :hops first :client)))
    (is (= "Real Issue" subject))
    (is (= "Real Author" from-name))
    (is (str/includes? body-html "real newsletter body"))
    (is (not (str/includes? body-html "Begin forwarded message")))
    (is (not (str/includes? body-html "Reply-To:")))
    (is (not (str/includes? body-html "Outer note")))))

(deftest recognizes-undashed-apple-plain-forward-marker
  (let [raw (str/join "\r\n"
                      ["From: Alice <alice@personal.test>"
                       "Subject: Fwd: Plain Apple Issue"
                       "Message-ID: <plain-apple@personal.test>"
                       "Content-Type: text/plain; charset=UTF-8"
                       ""
                       "Outer note."
                       ""
                       "Begin forwarded message:"
                       ""
                       "From: Plain Author <author@plain.example>"
                       "Subject: Plain Apple Issue"
                       "Date: Mon, 8 Jun 2026 12:00:00 +0000"
                       "To: Alice <alice@personal.test>"
                       ""
                       "The plain Apple newsletter body."
                       ""])
        {:keys [forwarded? subject from-name body-html forward]}
        (email/parse (.getBytes raw "UTF-8"))]
    (is (true? forwarded?))
    (is (= :plain (-> forward :hops first :client)))
    (is (= "Plain Apple Issue" subject))
    (is (= "Plain Author" from-name))
    (is (str/includes? body-html "plain Apple newsletter body"))
    (is (not (str/includes? body-html "Outer note")))))

(deftest extracts-a-forwarded-rfc822-attachment
  (let [raw (str/join "\r\n"
                      ["From: Alice Forwarder <alice@personal.test>"
                       "Subject: Fwd: Attached Dispatch"
                       "Message-ID: <attached-delivery@personal.test>"
                       "MIME-Version: 1.0"
                       "Content-Type: multipart/mixed; boundary=\"ATT\""
                       ""
                       "--ATT"
                       "Content-Type: text/plain; charset=UTF-8"
                       ""
                       "See attached."
                       "--ATT"
                       "Content-Type: message/rfc822"
                       "Content-Disposition: attachment"
                       ""
                       "From: Attached Author <author@attached.example>"
                       "Subject: Attached Dispatch"
                       "Message-ID: <original@attached.example>"
                       "Date: Mon, 8 Jun 2026 12:00:00 +0000"
                       "List-Unsubscribe: <https://attached.example/unsubscribe>"
                       "Content-Type: text/html; charset=UTF-8"
                       ""
                       "<p>The attached newsletter body.</p>"
                       "--ATT--"
                       ""])
        {:keys [message-id subject from-name body-html newsletter forward]}
        (email/parse (.getBytes raw "UTF-8"))]
    (is (= "<attached-delivery@personal.test>" message-id))
    (is (= "Attached Dispatch" subject))
    (is (= "Attached Author" from-name))
    (is (= "<original@attached.example>" (:source-message-id newsletter)))
    (is (= "https://attached.example/unsubscribe" (:unsubscribe-url newsletter)))
    (is (= :attached-message (-> forward :hops first :client)))
    (is (str/includes? body-html "attached newsletter body"))
    (is (not (str/includes? body-html "See attached")))))

(deftest peels-nested-inline-forwards
  (let [inner (str "<div class=\"gmail_quote\"><div class=\"gmail_attr\">---------- Forwarded message ---------<br>"
                   "From: Final Writer &lt;writer@final.example&gt;<br>Date: Mon, 8 Jun 2026 12:00:00 +0000<br>"
                   "Subject: Final Newsletter<br>To: Second Forwarder</div><p>The deepest newsletter body.</p></div>")
        outer (str "<p>First forwarder's note.</p><div class=\"gmail_quote\"><div class=\"gmail_attr\">"
                   "---------- Forwarded message ---------<br>From: Second Forwarder &lt;second@personal.test&gt;<br>"
                   "Date: Mon, 8 Jun 2026 13:00:00 +0000<br>Subject: Fwd: Final Newsletter<br>To: Alice</div>"
                   inner "</div>")
        {:keys [subject from-name body-html forward]}
        (email/parse (.getBytes (client-forward-eml "Fwd: Fwd: Final Newsletter" outer) "UTF-8"))]
    (is (= 2 (:depth forward)))
    (is (= "Final Newsletter" subject))
    (is (= "Final Writer" from-name))
    (is (str/includes? body-html "deepest newsletter body"))
    (is (not (str/includes? body-html "First forwarder's note")))))
