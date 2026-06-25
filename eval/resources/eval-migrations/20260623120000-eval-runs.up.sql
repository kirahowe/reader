-- Evals: non-destructive benchmark runs (ADR 0006). A run executes the real
-- inference path under a config (model / response-format) over the labeled set,
-- writing only here — never the production baseline or graph — so production
-- output is just one more thing to score, not something a run mutates. Counts
-- (tp/fp/fn/n) are stored; precision/recall/F1 are derived on read from them, so
-- there's a single source of truth and no reserved-word columns.
--
-- A run is created `running` and executed off the request thread (a real-model
-- run can take a while), then settled to `done` with its counts or `failed`
-- with an error — so `status` carries the lifecycle and the UI never shows a
-- half-scored run as a real result.
--
-- Same `--;;` rule as the other migrations: one statement per chunk.

CREATE TABLE eval_runs (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  feature     text NOT NULL CHECK (feature IN ('tagging','extraction')),
  config      jsonb NOT NULL DEFAULT '{}'::jsonb,
  model       text,
  status      text NOT NULL DEFAULT 'running' CHECK (status IN ('running','done','failed')),
  error       text,
  n           int NOT NULL DEFAULT 0,
  tp          int NOT NULL DEFAULT 0,
  fp          int NOT NULL DEFAULT 0,
  fn          int NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now()
)

--;;

-- One row per case in a run: what the pipeline proposed under the run's config,
-- for the per-case drill-down. Scoring is recomputed against current labels.
CREATE TABLE eval_run_results (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id         uuid NOT NULL REFERENCES eval_runs (id) ON DELETE CASCADE,
  readable_type  text,
  readable_id    uuid,
  subject_url    text,
  proposed       jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at     timestamptz NOT NULL DEFAULT now()
)

--;;

CREATE INDEX eval_run_results_run_idx ON eval_run_results (run_id)

--;;

CREATE INDEX eval_runs_feature_idx ON eval_runs (feature, created_at DESC)
