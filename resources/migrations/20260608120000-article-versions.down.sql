ALTER TABLE articles DROP CONSTRAINT articles_canonical_url_fetched_on_key

--;;

ALTER TABLE articles ADD CONSTRAINT articles_canonical_url_key UNIQUE (canonical_url)

--;;

ALTER TABLE articles DROP COLUMN fetched_on
