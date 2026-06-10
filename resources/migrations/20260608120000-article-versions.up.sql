-- Dated-version article identity: the same canonical URL fetched on different
-- days is a different version (its content can change), so identity becomes
-- (canonical_url, fetched_on) instead of canonical_url alone. fetched_on is the
-- paste date — when reader.ingest/start! creates the placeholder — defaulted by
-- the database; existing rows are backfilled from created_at.
--
-- Same `--;;` rule as the other migrations: one statement per chunk, no
-- trailing `;`.

ALTER TABLE articles ADD COLUMN fetched_on date NOT NULL DEFAULT current_date

--;;

UPDATE articles SET fetched_on = created_at::date

--;;

ALTER TABLE articles DROP CONSTRAINT articles_canonical_url_key

--;;

ALTER TABLE articles ADD CONSTRAINT articles_canonical_url_fetched_on_key UNIQUE (canonical_url, fetched_on)
