-- The user's word that an item's values of a facet are all named: every other value of the facet
-- is taken from it, as if rejected one by one ("all its universes are named")
CREATE TABLE facet_complete (
    item_id      VARCHAR(200)             NOT NULL,
    facet        VARCHAR(100)             NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, facet)
);
