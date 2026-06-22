# ADR 0005: Auto-tagging

- **Status**: Accepted
- **Date**: 2026-06-20

## Context

A reading queue grows faster than it's read. To help tame the backlog we want
readables **auto-categorized** on ingest — grouped with similar ones and
filterable by topic — and the same signal to seed an "internal" recommendation
feature later (the corpus being the user's own library).

The constraints that shaped the design:

- **Multi-user, shared readables.** A readable (`articles` / `papers` /
  `newsletter_issues`) is content-addressed and shared; only `queue_items` are
  per-user (ADR 0003). A topical tag describes the *content*, not one user's
  filing.
- **Cost shape.** The project prefers flat/fixed costs over usage-linear ones,
  and low-dependency, pure-Clojure solutions over heavy infra.
- **An existing pattern to mirror.** Ingestion already has a swappable
  enrichment abstraction — `:reader.ingest/entity-extractor`, a function behind
  an Integrant key, a Malli contract enforced at the boundary, deterministic
  today and LLM-backed later by config. Tagging is the same shape of problem.
- **Postgres everywhere, embedded in dev/test.** Dev and tests run an embedded
  Postgres with no extensions (ADR 0002/0004), so anything requiring a Postgres
  extension splits dev from prod.

The state of the art splits cleanly into two jobs with two different best tools:
*open-vocabulary tagging* is owned by LLMs (no good zero-training-data
alternative), while *semantic similarity and dedup* is owned by embeddings.

## Decision

**LLM for tags, embeddings for dedup and similarity, behind pluggable clients —
following the entity-extractor pattern, storing vectors as `jsonb` and comparing
them in Clojure.**

### Pluggable, OpenAI-compatible model clients

`reader.ai` exposes two plain functions behind Integrant keys — `:reader.ai/complete`
(one-shot chat-completion inference) and `:reader.ai/embed` — built on
`reader.http/post!` (http-kit + charred). **No new dependencies.** Both speak the
OpenAI-compatible wire shape (`/chat/completions`, `/embeddings`), the lowest
common denominator that OpenRouter (the default), OpenAI, Groq, and a local
Ollama/llamafile all serve, so the provider is a config swap, not a code change —
**no vendor lock-in**. Each is `nil` until its api-key is set (the URL defaults to
OpenRouter), so the credential is the single switch that turns tagging on.

We start with a cheap cloud model. Its cost is usage-linear but negligible at
personal scale (fractions of a cent per readable); the eval table (below) is the
instrument that tells us whether a local model is worth swapping in.

### The infer-tags abstraction

`reader.ingest.tag` is the swappable tagging abstraction, mirroring
`entity-extractor`: a function `(content, existing-vocab) → TagResult`, behind a
Malli `TagResult` contract whose caps (≤ 12 tags, ≤ 60-char labels) are the
guardrail on untrusted model output, enforced by `coerce`/`valid?` regardless of
implementation. The default is LLM-backed; a local-model implementation behind
the same contract is a wiring change.

### Reliable model output: structured outputs + defense-in-depth

An LLM in the loop returns text; we need a known shape. Three layers, strongest
first:

1. **Structured Outputs.** The completion carries a `response_format` — strict
   `json_schema` by default (the `gpt-4o-mini` default supports it), so the model
   is constrained to emit the tag shape rather than asked nicely to. The mode is a
   config knob (`:reader.ingest.tag/tagger :response-format`), since strict schema
   support isn't universal across OpenAI-compatible endpoints — drop to
   `:json-object` or `:none` for one that lacks it.
2. **The Malli boundary still validates** regardless of which mode (or provider)
   produced the output — `coerce`/`valid?` clamp caps and normalize labels. The
   schema is *types-only* precisely so the caps live in one enforced place; strict
   mode doesn't reliably enforce min/max anyway.
3. **A tolerant parser** salvages JSON from prose/fences for `:none`/`:json-object`
   providers. A response with *no* parseable tag object is a retryable
   `:unparseable-tags` failure — distinct from a parsed-but-empty result — so a
   model hiccup retries through the job queue rather than silently recording zero
   tags as success.

`temperature 0` keeps classification deterministic; `:max-tokens` caps a runaway
generation.

### Embedding-based dedup; no pgvector

The #1 failure mode of free-form tagging is vocabulary explosion. Guard it in two
layers: the LLM is fed the existing vocabulary and told to prefer it, and every
proposed label is **embedded and folded into an existing tag when cosine
similarity ≥ 0.90**, else created. The readable itself is embedded too, to seed
phase-2 recommendations.

Embeddings are stored as **`jsonb` arrays of floats** and compared with
**cosine similarity in Clojure** — *not* pgvector. The embedded Postgres in
dev/test has no vector extension, so `jsonb` keeps all three environments
identical; and at a personal library's scale brute-force cosine is sub-100ms, so
a vector index buys nothing until tens of thousands of vectors. `tags.slug` is
the stable hook to add a `vector` column if that day comes.

### Ownership: shared baseline + per-user override

The model-inferred baseline lives on the readable (`readable_tags`, computed
once, shared); a user's edits are a sparse delta on their queue item
(`queue_item_tags`: `add` / `suppress`). Effective tags resolve to
`baseline ∖ suppressions ∪ additions`. Re-tagging a readable propagates to every
user automatically while each user's customizations stand. (Schema in
[data-model.md](../data-model.md).)

### A `tag-readable` job, with a skip-when-unconfigured policy

Each ingest path (`reader.ingest`/`reader.papers`/`reader.domain.newsletters`)
enqueues a `tag-readable` job in the same transaction that finalizes the
readable. The job (`reader.ingest.tag-job`) runs the network calls *outside* the
DB transaction, then writes the baseline, the readable embedding, and a
`tagging_events` row in one transaction. It's idempotent (delete-then-insert), so
re-running re-tags cleanly.

Failures use the existing worker machinery: transient errors retry with backoff;
`missing-readable` and contract violations are `:fatal?` (no retry); a
permanently failed readable simply stays untagged. When no real model is
configured the job runs deterministic **stubs** in dev/test (so the pipeline —
and CI — works offline), but in prod `:require-model?` makes it record a
`:skipped` event and **reschedule itself** instead, so stub tags never land in
the shared corpus before the secrets do.

### Eval table

`tagging_events` mirrors `extraction_events`: one row per attempt with
first-class metric columns (outcome, model, tag count, latency) plus a jsonb
`provenance` bag, for offline evals and local-model comparison.

## Consequences

- The whole feature rides on existing dependencies (`http-kit`, `charred`,
  `malli`, `next.jdbc`) — no new libraries, no vector database, no Python.
- Tagging cost is usage-linear but bounded and tiny; embeddings and similarity
  are fixed-cost (own CPU). The provider is swappable for a fully-local,
  fixed-cost stack without touching callers.
- Readable embeddings are stored now, so phase-2 "more like this" is a query, not
  a reprocessing pass.
- **Gotcha for future keys:** because `ig/load-namespaces` (prod boot) requires
  the namespace named by a key's *namespace* part, the tagging keys are
  `:reader.ingest.tag/tagger` and `:reader.ingest.tag-job/handler` (matching the
  ns that holds each `defmethod`), not `:reader.ingest/…`. A mismatch boots fine
  under the test runner (which pre-loads every ns) but crashes a clean prod boot.
- **Deferred:** an `/admin/tagging` eval dashboard mirroring `/admin/extractions`,
  and the phase-2 recommendation UI over `readable_embeddings`.
