-- A tag bound to several entries of the catalogues, an entry to several tags: one pair a row. The
-- entry bound first names the tag. Made anew: the key of the tag alone is the index its reference
-- to the tags uses
CREATE TABLE normalized_tag_entries (
    tag       BIGINT                   NOT NULL REFERENCES normalized_tag (id) ON DELETE CASCADE,
    catalogue VARCHAR(100)             NOT NULL,
    entry     VARCHAR(100)             NOT NULL,
    bound_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tag, catalogue, entry)
);
INSERT INTO normalized_tag_entries (tag, catalogue, entry, bound_at) SELECT tag, catalogue, entry, bound_at FROM normalized_tag_entry;
DROP TABLE normalized_tag_entry;
ALTER TABLE normalized_tag_entries RENAME TO normalized_tag_entry;
CREATE INDEX normalized_tag_entry_entry ON normalized_tag_entry (catalogue, entry);
