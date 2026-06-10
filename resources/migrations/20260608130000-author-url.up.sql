-- Authors can carry a homepage/profile URL (from a byline's JSON-LD url or
-- sameAs). Nullable — most authors won't have one, and a byline that is *only*
-- a URL with no name is dropped at extraction rather than stored as a name.

ALTER TABLE authors ADD COLUMN url text
