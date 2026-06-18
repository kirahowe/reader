-- OpenAlex classifies some paper venues as conferences (proceedings); add a
-- matching affiliation type so the venue maps precisely instead of collapsing
-- to 'other'. See reader.papers/openalex-venue-types for the full mapping.
--
-- One statement per --;; chunk, no trailing ;.

ALTER TABLE affiliations DROP CONSTRAINT affiliations_type_check

--;;

ALTER TABLE affiliations ADD CONSTRAINT affiliations_type_check
  CHECK (type IN ('newspaper','magazine','blog','podcast','newsletter',
                  'journal','preprint','conference','institution','other'))
