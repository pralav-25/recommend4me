-- A tag the user ignores: it stays with its works, shown and found, but the model of the grades does
-- not read it — AO3's warnings, a site's tag of no matter to the user
ALTER TABLE normalized_tag ADD COLUMN ignored BOOLEAN DEFAULT FALSE NOT NULL;
