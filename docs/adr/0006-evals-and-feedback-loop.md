# ADR 0006: Evals tooling and the feedback loop

- **Status**: Accepted
- **Date**: 2026-06-22

## Context

Entity extraction (the `entity-extractor` abstraction over the ADR 0003 data
model) and auto-tagging (ADR 0005) both ship behind swappable, contract-bound
abstractions, and both write a first-class observability row per attempt —
`extraction_events`, `tagging_events` — plus a jsonb `provenance` bag. Both
features work but are mediocre: extraction coverage is thin, tags are noisy. We
have no instrument to (a) see *why* a given case is bad, (b) record what the
right answer was, or (c) measure whether a change to the model, prompt, output
shape, or threshold actually improves anything.

`/admin/extractions` is a read-only aggregate view; ADR 0005 deferred its
tagging twin. Neither drills into individual cases, neither carries the captured
provenance into the UI, and — as `reader.admin` itself notes — there is no
correction-label write path, so any "error rate" is only inferred.

What we want is a real eval loop: **collect labels and corrections, change the
pipeline, measure the delta** — and only then expose regeneration to users,
because regeneration is worthless until the pipeline can do better.

Constraints carried from ADRs 0002/0004/0005: low-dependency, pure-Clojure where
possible; flat/fixed cost over usage-linear; Postgres everywhere (embedded in
dev/test, Neon in prod); Integrant + EDN config; the product app stays a
focused, server-rendered, no-JS-framework product.

## Decision

### A separate eval app — same repo, same database, no inter-app API

The eval tool is a **second application in this repo** with its own entry point,
Integrant system, routes, and UI, deployed at its own subdomain
(`evals.themiscellany.app`) as a second Fly app, and runnable locally in dev. It
is **not** a separate service that talks to the reader over HTTP.

The decisive reason: the value of evals is *re-running the real inference path
under experimental configs*, and that logic (`reader.ai`, `reader.ingest.tag`,
`reader.ingest.entities`, `reader.extract`, `reader.domain.*`) is library code
here. Sharing the repo lets the eval app call it directly; sharing the database
— reading the reader's `public` tables, owning dedicated `eval_*` tables —
avoids any sync layer. An inter-app API would push every experimental variant
through HTTP or duplicate the inference code: more infra, less capability. The
separation that matters (process, deploy, access control, prod classpath) is
preserved; only the needless network boundary is dropped.

- **Code layout.** Eval namespaces live under a dedicated `eval/` source root
  behind an `:eval` deps alias that adds `eval/src` and eval resources. The
  reader prod build (`:prod`) never loads eval code; the eval prod build
  (`:eval`) is a superset (reader domain + eval).
- **Migrations.** The eval app owns its migrations against a separate migratus
  tracking table (`eval_schema_migrations`) in the same DB, so the two apps
  never contend over `schema_migrations`. Only `eval_*` tables are written.
- **Access.** Reuse Hanko token verification + a small operator allowlist
  (config, like the existing `admin-emails`). Local dev binds localhost. The
  handful of people sign in exactly as they do for the reader.
- **Charts.** Server-rendered SVG / plain HTML+CSS bars — no charting library.

### Front-end: Datastar over SSE, server-authoritative

The eval app must feel snappy (rapid keyboard labeling) without losing the
server-authoritative model. It uses **Datastar** (the `dev.data-star.clojure`
SDK over http-kit ≥ 2.9 beta, which adds SSE support) rather than a hand-written
JS layer:

- The **Workbench** verdict is a Datastar `@post` (sending the form). The handler
  writes the golden label and responds with an **SSE patch of the next case**
  (`#wb`) — so labeling advances with no full reload. Keyboard shortcuts live in
  a Datastar expression on `#wb`; flagging is still label-wrapped checkboxes +
  CSS `:has`, so the page works with no JS (the verdict handler branches on
  `datastar-request?`: SSE for Datastar, the plain form POST → redirect
  otherwise). The same pattern makes a benchmark **run** patch the runs list in
  place.
- State stays on the server: the client holds only transient UI state; every
  durable decision is a server write that streams back the next authoritative
  fragment. This is also the pilot for migrating the reader app off htmx onto
  Datastar.
- Cases and Overview are read-only — plain server-rendered HTML, no Datastar.

The Datastar runtime is vendored (`/static/vendor/datastar.js`, pinned). There
is no JS build step: Datastar covers interactivity server-side, so the app ships
no hand-written or bundled JS.

### Non-destructive benchmark runs

Experimental runs (a chosen set × a variant config — model / prompt /
response-format / dedup threshold) execute the real inference functions but
**write only to `eval_*` tables**, never to `readable_tags`, `authors`,
`affiliations`, or the shared baseline. Production output is one more thing to
score, never something a run mutates. Targeted runs select the set by filter
(failures, low-confidence, by domain/tag, a sample-N) rather than reprocessing
everything.

### The feedback loop is the point; regeneration is downstream

Order of operations: **collect** (golden labels + user-reported corrections) →
**measure** (score production output *and* benchmark runs against labels:
precision / recall / F1) → **change** (model / prompt / output / thresholds) →
**re-measure** → **then** expose regeneration in the product. Regeneration
before a better pipeline is a no-op — the tagger is deterministic at
`temperature 0` — so it ships last, and the regenerate path takes a variant
config rather than blindly re-calling.

### Tags are subjective and per-user; entities are objective and shared

This asymmetry drives the product-side changes:

- **Tags.** "These are bad, regenerate" is a *preference*. Regeneration produces
  a **per-user** result and never moves another user's tags. This adds a middle
  layer to ADR 0005's model: shared baseline (`readable_tags`) → **per-user
  personal baseline (`queue_item_baseline_tags`, new)**, present only after a
  user regenerates → per-user add/suppress (`queue_item_tags`). Effective tags
  resolve to `base ∖ suppress ∪ add`, where `base` is the user's personal
  baseline if present, else the shared baseline. The shared baseline is
  recomputed only by an operator action (after a pipeline improvement) and only
  reaches users who have not personalized.
- **Entities.** "This piece wasn't written by this person" is not a per-user
  opinion — authorship/affiliation is a fact about the content. So a user's
  "this is wrong" is a **correction signal** routed to the eval tool (a
  golden-label candidate); re-extraction recomputes the **shared** record for
  everyone. No per-user entity variants.

### Eval schema (sketch; exact columns settled per-phase)

- `eval_labels` — golden/corrected truth for a case (keyed by readable +
  feature): the correct tag set or correct entities, plus who labeled it and
  when. User-reported corrections land here as unverified candidates; an
  operator promotes them.
- `eval_runs` — one experimental run: variant config (model, prompt id,
  response-format, threshold), the target-set descriptor, timestamps.
- `eval_run_results` — per-case output of a run (proposed tags/entities +
  confidences), scored against `eval_labels`.

## Consequences

- No new runtime dependencies in the product app; the eval app adds only what
  charts/labeling need, and only on its own classpath.
- Two Fly apps, one Neon DB. Eval reads `public`, owns `eval_*`. A
  read-replica/role split is available later with no query changes.
- ADR 0005 is amended: tag ownership gains a per-user personal-baseline layer,
  and the deferred `/admin/tagging` dashboard is superseded by the eval app (the
  existing `/admin/extractions` page can stay or be folded in).
- Regeneration and shared re-extraction become product features only after the
  loop has driven a measurable improvement — they are explicitly sequenced last.

## Phases

1. Eval app skeleton + case inspection — drill-down over events + provenance +
   content; read-only.
2. Feedback collection — `eval_labels`; lightweight "report wrong tag / wrong
   author" affordances in the product writing correction candidates; scoring of
   current production output.
3. Benchmark runs — variant configs over selected sets, non-destructive, scored
   and compared (charts).
4. Apply to product — shared entity re-extraction; per-user tag regeneration
   (`queue_item_baseline_tags` + the resolution change).
