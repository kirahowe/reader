ALTER TABLE affiliations DROP CONSTRAINT affiliations_type_check

--;;

ALTER TABLE affiliations ADD CONSTRAINT affiliations_type_check
  CHECK (type IN ('newspaper','magazine','blog','podcast','newsletter',
                  'journal','preprint','other'))

--;;

DROP INDEX IF EXISTS affiliations_openalex_id_idx

--;;

DROP INDEX IF EXISTS affiliations_ror_idx

--;;

ALTER TABLE affiliations DROP COLUMN IF EXISTS openalex_id

--;;

ALTER TABLE affiliations DROP COLUMN IF EXISTS ror

--;;

DROP INDEX IF EXISTS authors_openalex_id_idx

--;;

DROP INDEX IF EXISTS authors_orcid_idx

--;;

ALTER TABLE authors DROP COLUMN IF EXISTS openalex_id

--;;

ALTER TABLE authors DROP COLUMN IF EXISTS orcid
