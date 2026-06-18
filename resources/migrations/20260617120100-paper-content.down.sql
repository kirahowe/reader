ALTER TABLE papers ALTER COLUMN pdf_object_key SET NOT NULL

--;;

ALTER TABLE papers DROP COLUMN IF EXISTS body_html
