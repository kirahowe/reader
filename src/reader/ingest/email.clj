(ns reader.ingest.email
  "Pure inbound-email extraction: raw .eml bytes -> a delivery envelope plus a
   normalized newsletter. Forward recognition happens before sanitization, while
   mail-client wrappers and embedded headers still exist. The outer delivery
   Message-ID is never confused with an attached/original Message-ID."
  (:require [charred.api :as json]
            [clojure.string :as str])
  (:import (jakarta.mail Message Multipart Part)
           (jakarta.mail.internet InternetAddress MimeMessage)
           (java.io ByteArrayInputStream)
           (java.net URI)
           (java.util Properties)
           (org.jsoup Jsoup)
           (org.jsoup.nodes Document Element)
           (org.jsoup.safety Safelist)))

(def extraction-version 2)

(def ^:private max-forward-depth 5)
(def ^:private max-mime-parts 100)

(def ^:private parse-json (json/parse-json-fn {}))

(defn- blank->nil [x]
  (when (and (string? x) (not (str/blank? x))) (str/trim x)))

(defn- ->message ^MimeMessage [^bytes raw]
  (MimeMessage. (jakarta.mail.Session/getInstance (Properties.))
                (ByteArrayInputStream. raw)))

(defn- header [^Message msg name]
  (some->> (try (.getHeader msg name) (catch Exception _ nil))
           seq
           (str/join ",")
           blank->nil))

(defn- from [^Message msg]
  (when-let [^InternetAddress a (first (try (.getFrom msg) (catch Exception _ nil)))]
    {:name  (some-> (.getPersonal a) blank->nil)
     :email (some-> (.getAddress a) blank->nil str/lower-case)}))

(defn- unsubscribe-url [^Message msg]
  (let [uris (map second (re-seq #"<([^>]+)>" (or (header msg "List-Unsubscribe") "")))]
    (some (fn [scheme]
            (first (filter #(str/starts-with? (str/lower-case %) scheme) uris)))
          ["https:" "http:" "mailto:"])))

(defn- envelope [^Message msg]
  (let [sender (from msg)]
    {:subject         (some-> (try (.getSubject msg) (catch Exception _ nil)) blank->nil)
     :from-name       (:name sender)
     :from-email      (:email sender)
     :sent-at         (some-> (try (.getSentDate msg) (catch Exception _ nil)) .toInstant)
     :message-id      (some-> (header msg "Message-ID") blank->nil)
     :unsubscribe-url (unsubscribe-url msg)
     :list-id         (header msg "List-ID")}))

(defn- attachment? [^Part part]
  (let [disposition (try (.getDisposition part) (catch Exception _ nil))]
    (boolean (and disposition (.equalsIgnoreCase ^String Part/ATTACHMENT disposition)))))

(defn- message-content [^Part part]
  (try (.getContent part) (catch Exception _ nil)))

(defn- merge-content [a b]
  {:html     (or (:html a) (:html b))
   :plain    (or (:plain a) (:plain b))
   :attached (into (vec (:attached a)) (:attached b))
   :parts    (+ (or (:parts a) 0) (or (:parts b) 0))})

(declare select-content)

(defn- multipart-content [^Multipart mp depth alternative? budget]
  (let [n (min (.getCount mp) @budget)
        children (mapv #(select-content (.getBodyPart mp %) (inc depth) budget) (range n))]
    (if alternative?
      {:html     (some :html children)
       :plain    (some :plain children)
       :attached (vec (mapcat :attached children))
       :parts    (reduce + 1 (map :parts children))}
      (reduce merge-content {:html nil :plain nil :attached [] :parts 1} children))))

(defn- select-content
  "Select the renderable body without promoting ordinary attachments. MIME
   alternatives prefer HTML locally; mixed/related messages use the first
   renderable inline child and separately retain attached message/rfc822 parts."
  [^Part part depth budget]
  (if (or (> depth max-forward-depth) (not (pos? @budget)))
    {:html nil :plain nil :attached [] :parts 0}
    (do
      (vswap! budget dec)
      (if (attachment? part)
        (if (and (<= depth max-forward-depth) (.isMimeType part "message/rfc822"))
          (let [c (message-content part)]
            {:html nil :plain nil :attached (if (instance? Message c) [c] []) :parts 1})
          {:html nil :plain nil :attached [] :parts 1})
        (cond
          (.isMimeType part "multipart/alternative")
          (if-let [c (message-content part)]
            (multipart-content ^Multipart c depth true budget)
            {:html nil :plain nil :attached [] :parts 1})

          (.isMimeType part "multipart/*")
          (if-let [c (message-content part)]
            (multipart-content ^Multipart c depth false budget)
            {:html nil :plain nil :attached [] :parts 1})

          (.isMimeType part "message/rfc822")
          (let [c (message-content part)]
            {:html nil :plain nil :attached (if (instance? Message c) [c] []) :parts 1})

          (.isMimeType part "text/html")
          (let [c (message-content part)]
            {:html (when (string? c) (blank->nil c)) :plain nil :attached [] :parts 1})

          (.isMimeType part "text/plain")
          (let [c (message-content part)]
            {:html nil :plain (when (string? c) (blank->nil c)) :attached [] :parts 1})

          :else {:html nil :plain nil :attached [] :parts 1})))))

(def ^:private header-labels
  {"from" :from "de" :from "von" :from "da" :from
   "subject" :subject "objet" :subject "betreff" :subject "oggetto" :subject
   "date" :date "sent" :date "envoyé" :date "gesendet" :date "inviato" :date
   "to" :to "à" :to "an" :to "a" :to})

(defn- header-lines [s]
  (-> (or s "")
      (str/replace \u00a0 \space)
      (str/replace #"[\r\t]+" " ")
      (str/split #"\n+")))

(defn- embedded-headers [s]
  (reduce (fn [m line]
            (if-let [[_ label value] (re-matches #"(?i)^\s*([^:]{1,16}):\s*(.+?)\s*$" line)]
              (if-let [k (get header-labels (str/lower-case label))]
                (assoc m k (blank->nil value))
                m)
              m))
          {}
          (header-lines s)))

(defn- parse-address [s]
  (try
    (when-let [^InternetAddress a (first (InternetAddress/parse (or s "") true))]
      {:name (some-> (.getPersonal a) blank->nil)
       :email (some-> (.getAddress a) blank->nil str/lower-case)})
    (catch Exception _ nil)))

(defn- parse-date [s]
  (when (blank->nil s)
    (try
      (let [raw (str "Date: " s "\r\nContent-Type: text/plain\r\n\r\nx")]
        (some-> (->message (.getBytes raw "UTF-8")) .getSentDate .toInstant))
      (catch Exception _ nil))))

(defn- elements-after [^Element el]
  (loop [node (.nextElementSibling el) out []]
    (if node (recur (.nextElementSibling node) (conj out node)) out)))

(defn- elements-html [els]
  (blank->nil (apply str (map #(.outerHtml ^Element %) els))))

(defn- embedded-header-text [^Element el]
  (if (= "table" (.tagName el))
    (str/join "\n" (map #(.text ^Element %) (.select el "tr")))
    (.wholeText el)))

(declare forward-subject?)

(def ^:private inline-forward-specs
  [{:client :gmail :header-selector ".gmail_quote .gmail_attr"
    :signals [:gmail-quote :gmail-forward-header]}
   ;; Apple Mail puts the original in a cite blockquote whose first compact div
   ;; is the forwarded header. The preceding marker is the corroborating signal.
   {:client :apple-mail :header-selector "blockquote[type=cite] > div"
    :marker #"(?i)begin forwarded message"
    :signals [:apple-forward-marker :cited-body]}
   ;; Outlook's reply and forward wrapper is shared, so the outer Fwd/Fw subject
   ;; is required to distinguish this from an ordinary reply.
   {:client :outlook :header-selector "#divRplyFwdMsg, .OutlookMessageHeader"
    :require-forward-subject? true
    :signals [:outlook-forward-header :following-body]}
   ;; Yahoo's wrapper also carries replies. A literal forward marker plus a
   ;; compact direct-child header block disambiguates it.
   {:client :yahoo :header-selector ".yahoo_quoted > div"
    :marker #"(?i)forwarded message"
    :signals [:yahoo-quote :yahoo-forward-marker]}
   {:client :thunderbird :header-selector ".moz-forward-container .moz-email-headers-table"
    :signals [:mozilla-forward-container :mozilla-header-table]}])

(defn- inline-forward [env html]
  (when-let [html (blank->nil html)]
    (let [doc (Jsoup/parse html)]
      (some (fn [{:keys [client header-selector marker require-forward-subject? signals]}]
              (some (fn [^Element h]
                      (let [headers (embedded-headers (embedded-header-text h))
                            body    (elements-html (elements-after h))]
                        (when (and (:from headers)
                                   (:subject headers)
                                   body
                                   (or (nil? marker) (re-find marker (.wholeText doc)))
                                   (or (not require-forward-subject?) (forward-subject? (:subject env))))
                          {:detected? true :client client :confidence 0.95 :signals signals
                           :headers headers :body-html body})))
                    (.select doc header-selector)))
            inline-forward-specs))))

(def ^:private plain-forward-marker
  #"(?i)^\s*-{2,}\s*(?:begin\s+)?forwarded\s+(?:message|email)\s*-{2,}\s*$")

(defn- plain-forward [plain]
  (when-let [plain (blank->nil plain)]
    (let [lines (vec (str/split plain #"\r?\n"))
          marker-idx (first (keep-indexed #(when (and (< %1 30)
                                                      (re-matches plain-forward-marker %2)) %1)
                                          lines))]
      (when marker-idx
        (let [after  (subvec lines (inc marker-idx))
              blank  (first (keep-indexed #(when (str/blank? %2) %1) after))
              hlines (if blank (subvec after 0 blank) [])
              headers (embedded-headers (str/join "\n" hlines))
              body    (when blank (blank->nil (str/join "\n" (subvec after (inc blank)))))]
          (when (and (:from headers) (:subject headers) (or (:date headers) (:to headers)) body)
            {:detected? true :client :plain :confidence 0.9
             :signals [:forward-marker :embedded-header-block]
             :headers headers :body-plain body}))))))

(defn- forward-subject? [s]
  (boolean (and s (re-find #"(?i)^\s*(?:fwd?|tr|wg)\s*:" s))))

(defn- strip-forward-prefixes [s]
  (loop [v s n 0]
    (if (and (< n 5) v (re-find #"(?i)^\s*(?:fwd?|tr|wg)\s*:" v))
      (recur (str/replace-first v #"(?i)^\s*(?:fwd?|tr|wg)\s*:\s*" "") (inc n))
      (blank->nil v))))

(defn- escape-html [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- plain-html [plain]
  (when-let [plain (blank->nil plain)]
    (->> (str/split plain #"(?:\r?\n){2,}")
         (map #(str "<p>" (-> % escape-html (str/replace #"\r?\n" "<br>")) "</p>"))
         (apply str))))

(defn- safe-uri? [s schemes]
  (try
    (let [u (URI. s)] (contains? schemes (some-> (.getScheme u) str/lower-case)))
    (catch Exception _ false)))

(defn- small-dimension? [s]
  (try (<= (Long/parseLong (or (some-> s (str/replace #"[^0-9].*$" "")) "999")) 1)
       (catch Exception _ false)))

(defn- tracker? [^Element img]
  (let [style (str/lower-case (.attr img "style"))]
    (or (small-dimension? (.attr img "width"))
        (small-dimension? (.attr img "height"))
        (re-find #"display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0(?:\D|$)" style))))

(def ^:private newsletter-safelist
  (doto (Safelist/relaxed)
    (.addAttributes "a" (into-array String ["target" "rel" "referrerpolicy"]))
    (.addAttributes "img" (into-array String ["loading" "decoding" "referrerpolicy"]))
    (.addProtocols "a" "href" (into-array String ["http" "https" "mailto"]))
    (.addProtocols "img" "src" (into-array String ["http" "https"]))))

(defn- normalize-body [html]
  (when-let [html (blank->nil html)]
    (let [raw (Jsoup/parseBodyFragment html)]
      (doseq [^Element img (.select raw "img")]
        (when (tracker? img) (.remove img)))
      (let [clean (Jsoup/clean (.html (.body raw)) "" newsletter-safelist)
            doc   (Jsoup/parseBodyFragment clean)]
        (doseq [^Element h1 (.select doc "h1")] (.tagName h1 "h2"))
        (doseq [^Element el (.select doc "table, th, td, img")]
          (.removeAttr el "width")
          (.removeAttr el "height"))
        (doseq [^Element a (.select doc "a[href]")]
          (let [href (.attr a "href")]
            (cond
              (str/starts-with? href "#") nil
              (safe-uri? href #{"http" "https"})
              (doto a (.attr "target" "_blank")
                    (.attr "rel" "noopener noreferrer")
                    (.attr "referrerpolicy" "no-referrer"))
              (safe-uri? href #{"mailto"}) nil
              :else (.removeAttr a "href"))))
        (doseq [^Element img (.select doc "img[src]")]
          (if (safe-uri? (.attr img "src") #{"http" "https"})
            (doto img (.attr "loading" "lazy") (.attr "decoding" "async")
                  (.attr "referrerpolicy" "no-referrer"))
            (.remove img)))
        (blank->nil (.html (.body doc)))))))

(defn- attr-map [^Document doc selector key-attr value-attr]
  (into {} (map (fn [^Element el] [(.attr el key-attr) (.attr el value-attr)]))
        (.select doc selector)))

(defn- json-ld-objects [^Document doc]
  (mapcat (fn [^Element el]
            (try
              (let [x (parse-json (.data el))]
                (cond (map? x) (if (sequential? (get x "@graph")) (get x "@graph") [x])
                      (sequential? x) x
                      :else []))
              (catch Exception _ [])))
          (.select doc "script[type=application/ld+json]")))

(defn- ld-name [x]
  (cond (string? x) (blank->nil x)
        (map? x) (or (ld-name (get x "name")) (ld-name (get x "@value")))
        (sequential? x) (some ld-name x)))

(defn- split-byline [s]
  (->> (str/split (str/replace (or s "") #"(?i)^\s*by\s+" "")
                  #"\s*(?:,|;|&|\band\b)\s*")
       (map blank->nil) (remove nil?) distinct vec))

(defn- person-like? [s]
  (and (blank->nil s)
       (<= 2 (count (str/split (str/trim s) #"\s+")) 4)
       (not (re-find #"(?i)newsletter|news|daily|weekly|digest|updates?|team|editors?" s))))

(defn- body-metadata [html sender]
  (let [doc       (Jsoup/parse (or html ""))
        metas     (attr-map doc "meta[name]" "name" "content")
        og        (attr-map doc "meta[property]" "property" "content")
        ld        (json-ld-objects doc)
        ld-author (some #(get % "author") ld)
        ld-names  (->> (if (sequential? ld-author) ld-author [ld-author]) (keep ld-name))
        visible   (some-> (.selectFirst doc "[rel=author], [itemprop=author], .byline, .author, [class*=byline]") .text blank->nil)
        declared  (some-> (or (get metas "author") (get og "article:author") visible) split-byline seq)
        fallback  (when (person-like? (:name sender)) [(:name sender)])
        names     (or (seq ld-names) declared fallback)
        author-source (cond (seq ld-names) :json-ld
                            declared :newsletter-html
                            fallback :sender-header)
        author-confidence (case author-source :json-ld 0.95 :newsletter-html 0.8 :sender-header 0.55 0.0)
        publisher (or (some #(ld-name (get % "publisher")) ld)
                      (blank->nil (get og "og:site_name")))
        canonical (some-> (.selectFirst doc "link[rel=canonical]") (.attr "href") blank->nil)]
    {:authors (mapv #(hash-map :name % :source author-source
                               :confidence author-confidence)
                    (or names []))
     :publication-name publisher
     :original-url (when (safe-uri? canonical #{"http" "https"}) canonical)}))

(defn- sld-name [email]
  (when-let [domain (some-> email (str/split #"@") second blank->nil)]
    (let [parts (str/split domain #"\.")
          label (if (>= (count parts) 2) (nth parts (- (count parts) 2)) (first parts))]
      (str/capitalize label))))

(defn- domain-alias [email]
  (some->> (some-> email (str/split #"@") second blank->nil)
           (str "@")))

(defn- url-origin [s]
  (try
    (let [u (URI. s)]
      (when (and (#{"http" "https"} (some-> (.getScheme u) str/lower-case)) (.getHost u))
        (str (.getScheme u) "://" (.getHost u))))
    (catch Exception _ nil)))

(defn- source-metadata [body-meta env]
  (when-let [name (or (:publication-name body-meta) (sld-name (:from-email env)))]
    {:name name
     :url (url-origin (:original-url body-meta))
     :sender-alias (domain-alias (:from-email env))
     :source (if (:publication-name body-meta) :newsletter-html :sender-domain)
     :confidence (if (:publication-name body-meta) 0.9 0.6)}))

(declare extract-message)

(defn- inline-result [env content]
  (or (inline-forward env (:html content)) (plain-forward (:plain content))))

(defn- peel-inline-forwards [env content depth]
  (if-let [inline (when (< depth max-forward-depth) (inline-result env content))]
    (let [headers  (:headers inline)
          sender   (parse-address (:from headers))
          next-env {:subject (or (:subject headers) (strip-forward-prefixes (:subject env)))
                    :from-name (:name sender) :from-email (:email sender)
                    :sent-at (parse-date (:date headers))
                    :message-id nil :unsubscribe-url nil :list-id nil}
          deeper   (peel-inline-forwards next-env
                                         {:html (:body-html inline)
                                          :plain (:body-plain inline)}
                                         (inc depth))]
      (update deeper :hops #(into [(dissoc inline :headers :body-html :body-plain)] %)))
    {:envelope env
     :raw-html (or (:html content) (plain-html (:plain content)))
     :hops []}))

(defn- extract-message [^Message msg depth]
  (let [env     (envelope msg)
        content (select-content msg depth (volatile! max-mime-parts))
        child   (when (and (< depth max-forward-depth)
                           (= 1 (count (:attached content)))
                           (forward-subject? (:subject env)))
                  (extract-message (first (:attached content)) (inc depth)))]
    (cond
      child
      (-> child
          (update :hops #(into [{:client :attached-message :confidence 1.0
                                 :signals [:forward-subject :single-rfc822-attachment]}] %)))

      :else
      (peel-inline-forwards env content depth))))

(defn parse
  "Parse raw `.eml` bytes into separate delivery and newsletter identities.
   Top-level normalized fields remain for the ingest facade during the schema
   transition; notably `:message-id` is always the outer delivery Message-ID."
  [^bytes raw]
  (let [outer      (->message raw)
        delivery   (envelope outer)
        extracted  (extract-message outer 0)
        original   (:envelope extracted)
        raw-html   (:raw-html extracted)
        body-meta  (body-metadata raw-html {:name (:from-name original)
                                            :email (:from-email original)})
        body-html  (normalize-body raw-html)
        hops       (vec (:hops extracted))
        forwarded? (boolean (seq hops))
        newsletter {:subject           (or (:subject original)
                                           (when forwarded? (strip-forward-prefixes (:subject delivery)))
                                           (:subject delivery))
                    :from-name         (:from-name original)
                    :from-email        (:from-email original)
                    :sent-at           (:sent-at original)
                    :source-message-id (:message-id original)
                    :unsubscribe-url   (:unsubscribe-url original)
                    :body-html         body-html
                    :authors           (:authors body-meta)
                    :source            (source-metadata body-meta original)
                    :original-url      (:original-url body-meta)}
        forward    {:detected? forwarded? :depth (count hops) :hops hops
                    :confidence (if forwarded?
                                  (apply min (map :confidence hops))
                                  1.0)}]
    (merge newsletter
           {:message-id (:message-id delivery)
            :delivery delivery :newsletter newsletter :forward forward
            :forwarded? forwarded? :extraction-version extraction-version
            :provenance {:forward forward
                         :metadata {:authors (mapv #(select-keys % [:source :confidence]) (:authors body-meta))
                                    :source (some-> (:source newsletter)
                                                    (select-keys [:source :confidence]))}}})))
