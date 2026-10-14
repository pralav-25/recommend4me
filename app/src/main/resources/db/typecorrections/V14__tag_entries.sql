-- A normalized tag bound by the user to an entry of a catalogue (Wikidata): the entry names the tag
-- in every language of the user, its other names are the tag's too. One entry a tag at most; kept
-- with the merges, the user's word on the tags
CREATE TABLE normalized_tag_entry (
    tag       BIGINT                   NOT NULL PRIMARY KEY REFERENCES normalized_tag (id) ON DELETE CASCADE,
    catalogue VARCHAR(100)             NOT NULL,
    entry     VARCHAR(100)             NOT NULL,
    bound_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX normalized_tag_entry_entry ON normalized_tag_entry (catalogue, entry);

-- The names of the entries the tags are bound to, as the catalogue gave them when asked: a label,
-- the aliases, a description of every language asked; read again when the user asks
CREATE TABLE tag_entry (
    catalogue  VARCHAR(100)             NOT NULL,
    entry      VARCHAR(100)             NOT NULL,
    url        VARCHAR(1000)            NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (catalogue, entry)
);

-- kind: L - the label, A - an alias, D - the description
CREATE TABLE tag_entry_text (
    catalogue  VARCHAR(100)  NOT NULL,
    entry      VARCHAR(100)  NOT NULL,
    language   VARCHAR(20)   NOT NULL,
    kind       CHAR(1)       NOT NULL,
    position   INT           NOT NULL,
    text       VARCHAR(2000) NOT NULL,
    PRIMARY KEY (catalogue, entry, language, kind, position),
    FOREIGN KEY (catalogue, entry) REFERENCES tag_entry (catalogue, entry) ON DELETE CASCADE
);
