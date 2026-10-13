-- THE DRAFTS OF THE LAYER: the values a source drafts of a work from its own values on the site as
-- the dictionary of universes matches them — the characters its tags name, their pairings, its
-- universes — before the user says anything. Made again whenever the work or the dictionary
-- changes; the user's answers override them, kept apart
CREATE TABLE layer_draft (
    source     VARCHAR(100)  NOT NULL,
    item_id    VARCHAR(200)  NOT NULL,
    facet      VARCHAR(100)  NOT NULL,
    value_key  VARCHAR(500)  NOT NULL,
    -- The site's value it was read from, as written
    site_value VARCHAR(1000) NOT NULL,
    PRIMARY KEY (source, item_id, facet, value_key)
);
CREATE INDEX layer_draft_facet ON layer_draft (source, facet);
