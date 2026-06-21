(ns reader.ingest.tag
  "The infer-tags abstraction: propose topical tags for a readable from its
   content. The default implementation is LLM-backed (over the pluggable
   reader.ai completion fn); a local-model implementation satisfies the same TagResult
   contract and is wired in by config — the eval pass later tells us whether the
   swap is worth it. A stub (deriving tags from the title) lets dev/test boot
   without a model API key.

   `TagResult` is the contract every implementation must satisfy; its caps
   (<=12 tags, <=60-char labels) are the guardrail on untrusted model output,
   enforced at the boundary by `coerce` + `valid?` regardless of implementation.
   Embedding-based dedup of the proposed labels is a *downstream* concern
   (reader.domain.tags) — this namespace only proposes."
  (:require [clojure.string :as str]
            [charred.api :as json]
            [integrant.core :as ig]
            [malli.core :as m]))

;; ── contract ─────────────────────────────────────────────────────────────

(def TagProposal
  [:map {:closed true}
   [:label [:string {:min 1 :max 60}]]
   [:confidence [:double {:min 0.0 :max 1.0}]]])

(def TagResult
  "Output contract of the infer-tags abstraction."
  [:map {:closed true}
   [:tags [:vector {:max 12} TagProposal]]
   [:model {:optional true} [:maybe :string]]])

(defn valid? [x] (m/validate TagResult x))
(defn explain [x] (m/explain TagResult x))

(def ^:private max-tags 12)
(def ^:private max-label-len 60)

(defn- truncate [s n] (when s (subs s 0 (min (count s) n))))

(defn- clamp-label
  "Normalize a proposed label: trimmed, lowercased, <=60 chars, or nil if blank.
   Lowercasing keeps the vocabulary from forking on case alone."
  [s]
  (some-> s str str/trim not-empty str/lower-case (truncate max-label-len)))

(defn- clamp-conf [c]
  (-> (if (number? c) (double c) 1.0) (max 0.0) (min 1.0)))

(defn coerce
  "Clamp an infer-tags result to the TagResult caps: <=12 tags, labels
   normalized (dropping blanks), confidences clamped to [0,1]. The boundary
   guard on untrusted model output."
  [result]
  (update result :tags
          (fn [tags]
            (into [] (comp (keep (fn [t]
                                   (when-let [l (clamp-label (:label t))]
                                     {:label l :confidence (clamp-conf (:confidence t))})))
                           (take max-tags))
                  (or tags [])))))

;; ── prompt + parse (pure) ────────────────────────────────────────────────

(defn system-prompt
  "The classifier instruction, with the existing vocabulary inlined so the model
   reuses it before minting near-duplicates."
  [existing-labels]
  (str "You assign topical tags to reading material so similar items can be grouped.\n"
       "Return 1 to 6 broad, reusable tags (a topic, not the article's title) as JSON:\n"
       "{\"tags\":[{\"label\":\"...\",\"confidence\":0.0-1.0}]}\n"
       "Prefer a tag from the existing vocabulary when one fits; invent a new tag only when none does.\n"
       "Existing vocabulary: "
       (if (seq existing-labels) (str/join ", " existing-labels) "(none yet)")
       "\nRespond with only the JSON object."))

(defn user-content
  "The readable's signal for the model: title plus a bounded slice of abstract
   and body text (boilerplate-light, token-budget-friendly)."
  [{:keys [title abstract text]}]
  (str/join "\n"
            (remove nil?
                    [(when title (str "Title: " title))
                     (when abstract (str "Abstract: " (truncate abstract 600)))
                     (when text (str "Excerpt: " (truncate text 600)))])))

(defn build-messages [content existing-labels]
  [{:role "system" :content (system-prompt existing-labels)}
   {:role "user" :content (user-content content)}])

(def ^:private read-json (json/parse-json-fn {:key-fn keyword}))

(defn- json-object
  "The first balanced JSON object substring of `text`, tolerating ```json fences
   or stray prose around it. nil when there's no object."
  [text]
  (let [s (str text)
        a (str/index-of s "{")
        b (str/last-index-of s "}")]
    (when (and a b (< a b)) (subs s a (inc b)))))

(defn parse
  "Parse a model response into a raw seq of {:label :confidence} proposals.
   Tolerant of fences/prose; returns [] when nothing parseable is found."
  [text]
  (if-let [obj (json-object text)]
    (->> (:tags (read-json obj))
         (map (fn [t] {:label (:label t) :confidence (:confidence t)}))
         vec)
    []))

;; ── default implementation + stub ────────────────────────────────────────

(defn llm-tagger
  "The default infer-tags implementation: (fn [content existing-labels] -> TagResult)
   over a completion fn (reader.ai/complete). `model` is recorded for evals."
  [complete-fn model]
  (fn [content existing-labels]
    {:tags  (parse (complete-fn (build-messages content existing-labels)))
     :model model}))

(def ^:private stopwords
  #{"the" "a" "an" "and" "or" "of" "to" "in" "on" "for" "with" "how" "why"
    "what" "is" "are" "your" "you" "this" "that" "from" "by" "at" "as"})

(defn stub-tags
  "Cheap title-derived tags so dev/test exercise the pipeline without a model:
   the first couple of non-trivial title words become low-confidence tags."
  [{:keys [title]}]
  (->> (str/split (str/lower-case (or title "")) #"[^a-z0-9]+")
       (remove str/blank?)
       (remove stopwords)
       (filter #(> (count %) 2))
       distinct
       (take 2)
       (mapv (fn [w] {:label w :confidence 0.5}))))

(defn stub-tagger
  "A model-free infer-tags fn (title-derived tags) — the dev/test fallback the
   tag-job uses when no real tagger is configured."
  []
  (fn [content _existing-labels] {:tags (stub-tags content) :model "stub"}))

(defmethod ig/init-key :reader.ingest.tag/tagger
  ;; The infer-tags abstraction over the `complete` (inference) fn, or nil when
  ;; none is configured — the tag-job then runs the stub (dev/test) or skips
  ;; (prod), so the stub-vs-skip policy lives in one place alongside the embedder.
  ;; Swap the LLM for a local model by pointing `complete` at a different provider.
  [_ {:keys [complete model]}]
  (when complete (llm-tagger complete (or model "unknown"))))
