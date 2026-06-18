ALTER TABLE affiliations DROP CONSTRAINT affiliations_type_check

--;;

ALTER TABLE affiliations ADD CONSTRAINT affiliations_type_check
  CHECK (type IN ('newspaper','magazine','blog','podcast','newsletter',
                  'journal','preprint','institution','other'))
