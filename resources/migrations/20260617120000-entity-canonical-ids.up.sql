-- Stable external identifiers for canonical entity resolution: authors by ORCID,
-- institutions by ROR, both with their OpenAlex ID (always present from our
-- source). Partial-unique so the many id-less rows (name-only authors/outlets)
-- coexist; identity dedups on these when present and slug is just a URL handle.
-- Also widen the affiliation type enum to cover author institutions.
--
-- One statement per --;; chunk, no trailing ;.

ALTER TABLE authors ADD COLUMN orcid text

--;;

ALTER TABLE authors ADD COLUMN openalex_id text

--;;

CREATE UNIQUE INDEX authors_orcid_idx ON authors (orcid) WHERE orcid IS NOT NULL

--;;

CREATE UNIQUE INDEX authors_openalex_id_idx ON authors (openalex_id) WHERE openalex_id IS NOT NULL

--;;

ALTER TABLE affiliations ADD COLUMN ror text

--;;

ALTER TABLE affiliations ADD COLUMN openalex_id text

--;;

CREATE UNIQUE INDEX affiliations_ror_idx ON affiliations (ror) WHERE ror IS NOT NULL

--;;

CREATE UNIQUE INDEX affiliations_openalex_id_idx ON affiliations (openalex_id) WHERE openalex_id IS NOT NULL

--;;

ALTER TABLE affiliations DROP CONSTRAINT affiliations_type_check

--;;

ALTER TABLE affiliations ADD CONSTRAINT affiliations_type_check
  CHECK (type IN ('newspaper','magazine','blog','podcast','newsletter',
                  'journal','preprint','institution','other'))
