(ns reader.ingest.schema
  "Malli contracts for the ingest pipeline — the explicit, enforceable
   interface between extraction and the rest of the system.

   `EntityResult` is the seam every entity-extraction implementation must
   satisfy: the deterministic metadata reader today (reader.ingest.entities),
   and a future LLM-backed extractor wired in by config tomorrow. Its caps
   (max author count + name length) are deliberate — they are also the
   guardrail on untrusted model output, enforced at the boundary regardless of
   which implementation produced the result. Swapping in the LLM is a wiring
   change behind this contract, not a change to any consumer."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def Source
  "Where a value came from — its provenance. `:llm` is reserved for the future
   model-backed extractor so the eval dashboard can group by it from day one."
  [:enum :json-ld :og :meta :heuristic :domain :rel-author
   :title-tag :html-lang :canonical :url :llm])

(def Confidence [:double {:min 0.0 :max 1.0}])

(def Author
  [:map {:closed true}
   [:name [:string {:min 1 :max 200}]]
   [:source Source]
   [:confidence Confidence]
   ;; Optional homepage/profile URL (JSON-LD url / sameAs). Present only when the
   ;; byline declared one for a *named* author.
   [:url {:optional true} :string]])

(def Affiliation
  [:maybe
   [:map {:closed true}
    [:name [:string {:min 1 :max 200}]]
    [:source Source]
    [:confidence Confidence]]])

(def EntityResult
  "Output contract of the entity-extraction seam. The 50-author / 200-char
   caps bound adversarial (e.g. LLM-induced) output."
  [:map {:closed true}
   [:authors [:vector {:max 50} Author]]
   [:affiliation Affiliation]
   [:overall-confidence Confidence]])

(def Field
  "A resolved metadata field with its provenance."
  [:map {:closed true}
   [:value [:maybe :any]]
   [:source [:maybe :keyword]]])

(def BodyResult
  [:map {:closed true}
   [:html [:maybe :string]]
   [:text :string]
   [:word-count :int]
   [:reading-time-secs :int]
   [:extractor :keyword]
   [:confidence Confidence]
   [:signals :map]])

(def ExtractionContext
  "What reader.ingest.extract produces and the entity seam consumes."
  [:map
   [:url :string]
   [:signals [:map
              [:json-ld [:sequential :map]]
              [:og [:map-of :string :string]]
              [:meta [:map-of :string :string]]
              [:title-tag {:optional true} [:maybe :string]]
              [:html-lang {:optional true} [:maybe :string]]
              [:canonical {:optional true} [:maybe :string]]]]
   [:fields [:map-of :keyword Field]]
   [:body BodyResult]])

(defn valid-entities? [x] (m/validate EntityResult x))
(defn explain-entities [x] (m/explain EntityResult x))

(def ^:private max-name-len 200)
(def ^:private max-authors 50)

(defn- clamp-name [s]
  (let [t (some-> s str str/trim not-empty)]
    (when t (subs t 0 (min (count t) max-name-len)))))

(defn coerce-entities
  "Clamp a seam's result to the EntityResult caps (<=50 authors, <=200-char
   names), dropping any author left blank. The boundary guard on untrusted seam
   output (e.g. a future LLM extractor): applied to every implementation's
   result before it reaches the database, so the contract's caps actually bind
   regardless of which implementation produced the result."
  [{:keys [affiliation] :as result}]
  (-> result
      (update :authors
              (fn [authors]
                (into [] (comp (keep (fn [a] (when-let [n (clamp-name (:name a))]
                                               (assoc a :name n))))
                               (take max-authors))
                      authors)))
      (assoc :affiliation
             (when affiliation
               (when-let [n (clamp-name (:name affiliation))]
                 (assoc affiliation :name n))))))
