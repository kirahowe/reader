-- Persist the failing job's error-class (from the handler's ex-data) alongside
-- last_error, so the poll UI can tell an honest "not indexed yet" from a hard
-- failure without parsing the message string.
--
-- One statement per --;; chunk, no trailing ;.

ALTER TABLE jobs ADD COLUMN error_class text
