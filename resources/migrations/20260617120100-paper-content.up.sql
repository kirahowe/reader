-- Papers can carry reflowed body HTML (arXiv/ar5iv), and a paper added by link
-- has no uploaded PDF, so pdf_object_key becomes nullable.
--
-- One statement per --;; chunk, no trailing ;.

ALTER TABLE papers ADD COLUMN body_html text

--;;

ALTER TABLE papers ALTER COLUMN pdf_object_key DROP NOT NULL
