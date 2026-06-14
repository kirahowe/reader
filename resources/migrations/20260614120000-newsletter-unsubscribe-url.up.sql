-- The newsletter's own unsubscribe target, lifted from the List-Unsubscribe
-- header (RFC 2369) at ingest and surfaced as a one-click link in the reader.
-- Nullable — not every newsletter sends the header.
--
-- One statement per --;; chunk, no trailing ; (see the init migration).

ALTER TABLE newsletter_issues ADD COLUMN unsubscribe_url text
