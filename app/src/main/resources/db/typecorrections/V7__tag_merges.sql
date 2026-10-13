-- THE USER'S WORD ON THE SITES' FREE TAGS of a content type, every site's together. A tag of the
-- application's layer is a site's tag by its name, any case ("Магия" and "магия " one); the user
-- merges such tags into one and tells tags apart. Nothing is merged but by the user.

-- A tag merged into another: [member] — the key of a tag of the layer, its name lowercased — is the
-- tag [tag] from then on, its works and the user's answers on it
CREATE TABLE tag_merge (
    facet     VARCHAR(100)             NOT NULL,
    member    VARCHAR(500)             NOT NULL,
    tag       VARCHAR(500)             NOT NULL,
    merged_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (facet, member)
);

-- What a tag is shown by: the name the user gave it, else the one worked out of the names its works
-- have on the sites — the most used among the works liked, the graded, all of them, the first by
-- the alphabet ([rule] says which); rewritten as the works and their grades change
CREATE TABLE tag_name (
    facet VARCHAR(100)  NOT NULL,
    tag   VARCHAR(500)  NOT NULL,
    name  VARCHAR(1000),
    shown VARCHAR(1000),
    rule  VARCHAR(20),
    PRIMARY KEY (facet, tag)
);

-- Two tags the user said are different: not offered as alike again. The pair in the order of its keys
CREATE TABLE tag_distinct (
    facet     VARCHAR(100)             NOT NULL,
    first_tag VARCHAR(500)             NOT NULL,
    other_tag VARCHAR(500)             NOT NULL,
    said_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (facet, first_tag, other_tag)
);
