-- Evals: golden/corrected labels for tagging + extraction cases (ADR 0006).
-- One row per (feature, case) — the operator's confirmed-or-corrected truth,
-- materialized at label time (the golden set is stored, not a verdict relative
-- to a moving production output), so scoring can re-run against current
-- production anytime. Owned by the evals app on its own migratus tracking table
-- (eval_schema_migrations); it reads the reader's public tables but writes only
-- here. Keyed loosely: tagging by (readable_type, readable_id), extraction by
-- subject_url (the article may not exist when an attempt failed). NULLS NOT
-- DISTINCT so the unused key columns collapse to one row per case.
--
-- Same `--;;` rule as the reader migrations: one statement per chunk, no
-- trailing `;`.

CREATE TABLE eval_labels (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  feature       text NOT NULL CHECK (feature IN ('tagging','extraction')),
  readable_type text,
  readable_id   uuid,
  subject_url   text,
  label         jsonb NOT NULL,
  labeled_by    text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (feature, readable_type, readable_id, subject_url)
)

--;;

CREATE INDEX eval_labels_feature_idx ON eval_labels (feature)
