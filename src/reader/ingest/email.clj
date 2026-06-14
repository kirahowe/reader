(ns reader.ingest.email
  "Pure MIME parsing for inbound newsletters: raw .eml bytes -> the fields we
   store {:subject :from-name :from-email :sent-at :message-id :unsubscribe-url
   :body-html}. Uses
   Jakarta Mail (Angus) for robust multipart / encoded-word / quoted-printable
   decoding, and jsoup to sanitize the body — newsletter HTML is hostile, so the
   stored body is cleaned here, at the ingest boundary, then rendered raw later
   (the same trust model as article bodies). No IO."
  (:require [clojure.string :as str])
  (:import (jakarta.mail Multipart Part)
           (jakarta.mail.internet InternetAddress MimeMessage)
           (java.io ByteArrayInputStream)
           (java.util Properties)
           (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)))

(defn- ->message ^MimeMessage [^bytes raw]
  (MimeMessage. (jakarta.mail.Session/getInstance (Properties.))
                (ByteArrayInputStream. raw)))

(defn- find-content
  "Depth-first search for the first leaf part of mime type `mime`, returning its
   String content, or nil. Recurses into multiparts (multipart/alternative,
   /mixed, /related)."
  [^Part part mime]
  (cond
    (.isMimeType part mime)
    (let [c (.getContent part)] (when (string? c) c))

    (.isMimeType part "multipart/*")
    (let [mp ^Multipart (.getContent part)]
      (first (keep #(find-content (.getBodyPart mp %) mime) (range (.getCount mp)))))

    :else nil))

(defn- escape-html [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- raw-body
  "The best body HTML: the text/html alternative, else the text/plain wrapped as
   preformatted (escaped) HTML, else nil."
  [^MimeMessage msg]
  (or (not-empty (find-content msg "text/html"))
      (some-> (find-content msg "text/plain") not-empty
              (->> escape-html (format "<pre>%s</pre>")))))

(defn- sanitize [html]
  (when html (Jsoup/clean html (Safelist/relaxed))))

(defn- from [^MimeMessage msg]
  (when-let [^InternetAddress a (first (try (.getFrom msg) (catch Exception _ nil)))]
    {:name  (some-> (.getPersonal a) str/trim not-empty)
     :email (some-> (.getAddress a) str/trim str/lower-case not-empty)}))

(defn- unsubscribe-url
  "The preferred unsubscribe target from the List-Unsubscribe header (RFC 2369):
   its angle-bracketed URIs, picking https, else http, else mailto. nil when the
   header is absent or carries no recognizable URI."
  [^MimeMessage msg]
  (let [headers (.getHeader msg "List-Unsubscribe")
        raw     (when (seq headers) (str/join "," headers))
        uris    (map second (re-seq #"<([^>]+)>" (or raw "")))]
    (some (fn [scheme] (first (filter #(str/starts-with? (str/lower-case %) scheme) uris)))
          ["https:" "http:" "mailto:"])))

(defn parse
  "Parse raw .eml `bytes` into the stored newsletter fields. The body is
   sanitized; everything else is best-effort and nil when absent."
  [^bytes raw]
  (let [msg (->message raw)
        f   (from msg)]
    {:subject    (some-> (.getSubject msg) str/trim not-empty)
     :from-name  (:name f)
     :from-email (:email f)
     :sent-at    (some-> (try (.getSentDate msg) (catch Exception _ nil)) .toInstant)
     :message-id (some-> (try (.getMessageID msg) (catch Exception _ nil)) str/trim not-empty)
     :unsubscribe-url (unsubscribe-url msg)
     :body-html  (sanitize (raw-body msg))}))
