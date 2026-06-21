(ns reader.ai
  "Pluggable model clients: a completion (text-inference) fn and an embedding fn,
   each built from config and speaking the OpenAI-compatible HTTP API — so the
   same code targets OpenAI, a local Ollama/llamafile, Groq, and others by config
   alone. \"Completion\" here is one-shot inference over the chat-completions
   protocol (the lowest common denominator every endpoint, cloud or local,
   speaks) — there is no conversation. Both are plain functions behind Integrant
   keys (`:reader.ai/complete`, `:reader.ai/embed`), so a provider with a
   different wire shape is a key swap, not a change to any caller.

   Network is reader.http/post! (http-kit + charred) — no new dependencies."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [integrant.core :as ig]
            [reader.http :as http]))

(def ^:private parse-json (json/parse-json-fn {:key-fn keyword}))

(defn- post-json!
  "POST a JSON `body` to `url` with optional bearer `api-key`; return the parsed
   response. Throws ex-info on transport failure or non-2xx — 401/403 are
   `:fatal?` (retrying a bad key is pointless), the rest are retryable."
  [url api-key body]
  (let [{:keys [status body error]}
        @(http/post! url body {:headers (when api-key {"Authorization" (str "Bearer " api-key)})})]
    (cond
      error
      (throw (ex-info "model request failed" {:error-class :model-transport :cause error}))
      (contains? #{401 403} status)
      (throw (ex-info "model auth rejected" {:error-class :model-auth :fatal? true :status status}))
      (not (<= 200 status 299))
      (throw (ex-info "model request returned non-2xx"
                      {:error-class :model-status :status status :body body}))
      :else (parse-json body))))

(defn complete
  "One-shot inference: `messages` is a vector of {:role :content} (the
   chat-completions wire format — system instruction + user input, not a
   conversation); returns the model's reply text. `config`: {:api-url :api-key
   :model}. `temperature 0` keeps it deterministic for classification."
  [{:keys [api-url api-key model]} messages]
  (-> (post-json! (str api-url "/chat/completions") api-key
                  {:model model :messages messages :temperature 0})
      (get-in [:choices 0 :message :content])))

(defn embed
  "Embed `input` (a string, or a seq of strings); returns a vector of float
   vectors, one per input, in input order. `config`: {:api-url :api-key :model
   :dimensions?} — :dimensions truncates the vector when the provider supports
   Matryoshka embeddings (smaller rows, ample for similarity at this scale)."
  [{:keys [api-url api-key model dimensions]} input]
  (let [inputs (if (string? input) [input] (vec input))
        body   (cond-> {:model model :input inputs}
                 dimensions (assoc :dimensions dimensions))]
    (->> (:data (post-json! (str api-url "/embeddings") api-key body))
         (sort-by :index)
         (mapv (fn [d] (mapv double (:embedding d)))))))

;; ── stub embedder (no model API; dev/test) ───────────────────────────────

(defn- stub-vector
  "A deterministic pseudo-embedding for `s`: a bag-of-tokens vector hashed into
   `dims` buckets. Content-sensitive enough that distinct strings get distinct
   vectors (so dev/test dedup behaves), with no model call. Not semantic."
  [s dims]
  (let [v (double-array dims)]
    (doseq [tok (re-seq #"[a-z0-9]+" (str/lower-case (str s)))]
      (let [i (mod (Math/abs (int (hash tok))) dims)]
        (aset v i (+ (aget v i) 1.0))))
    (vec v)))

(defn stub-embed
  "An embed fn that needs no model API — for dev/test without credentials."
  ([] (stub-embed 64))
  ([dims] (fn [input] (mapv #(stub-vector % dims) (if (string? input) [input] (vec input))))))

;; ── Integrant: the pluggable provider fns ────────────────────────────────

(defmethod ig/init-key :reader.ai/complete
  ;; nil when no endpoint is configured, so the tagger falls back to its stub.
  [_ {:keys [api-url] :as config}]
  (when api-url (fn complete-fn [messages] (complete config messages))))

(defmethod ig/init-key :reader.ai/embed
  ;; nil when no endpoint is configured, mirroring :reader.ai/complete. With no real
  ;; embedder the tag-readable job decides what to do — run the stub (dev/test)
  ;; or skip (prod) — per its :require-model? policy, so stub vectors never land
  ;; in the shared baseline by accident.
  [_ {:keys [api-url] :as config}]
  (when api-url (fn embed-fn [input] (embed config input))))
