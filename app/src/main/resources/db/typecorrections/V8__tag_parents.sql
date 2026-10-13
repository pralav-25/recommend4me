-- A tag the user put under another: a work with [child] has [parent] too, and a work the user said
-- has not [parent] has not [child] either. A tag may have several parents; no tag is its own ancestor
CREATE TABLE tag_parent (
    facet     VARCHAR(100)             NOT NULL,
    child     VARCHAR(500)             NOT NULL,
    parent    VARCHAR(500)             NOT NULL,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (facet, child, parent)
);
