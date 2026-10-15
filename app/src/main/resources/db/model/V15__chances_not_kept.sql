-- The chances of the works' values are not kept: they are made when a work is read, by the model
-- of each facet kept here, from the work's vectors and values (user, 2026-10-15). What every work's
-- chances were made of goes with them.
DROP TABLE facet_suggestion;
DROP TABLE facet_suggestion_basis;

-- The values a facet's model was fitted to choose from, in its order, one a line: a work read alone
-- is judged among them without reading every work's values
ALTER TABLE facet_suggester ADD COLUMN value_keys CLOB;
