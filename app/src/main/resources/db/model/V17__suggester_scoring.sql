-- WHAT SCORING A WORK NEEDS, APART FROM EVERY VALUE'S NAMES: the model of a facet as a product of a
-- work's features with every value's kept coordinates and bias, of the values of value_keys in
-- their order — made once at the fitting, one array of numbers, read whole by a page of twenty works
-- in place of the dictionary and the names of every value (user, 2026-10-16). Null of a suggester
-- without one, and of a model fitted before: made by the next refresh.
ALTER TABLE facet_suggester ADD COLUMN scoring BLOB;

-- How many times a work's own texts name each value of a facet — a character in its chapters, a
-- universe by its characters' names — counted at the fitting, which reads every work's texts against
-- the names of every value: one line a work, "<value key>\t<count>" a line. A page reads those of its
-- works alone, never the dictionary's names. Counted again with every fitting: a name added to the
-- dictionary fits the facet again.
CREATE TABLE facet_mention (
    source  VARCHAR(100) NOT NULL,
    item_id VARCHAR(200) NOT NULL,
    facet   VARCHAR(100) NOT NULL,
    counts  CLOB         NOT NULL,
    PRIMARY KEY (source, item_id, facet)
);
