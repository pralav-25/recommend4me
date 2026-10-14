-- What an entry of a catalogue is a kind of, as the catalogue said when asked: "satire" right under
-- "humor". Read up from every entry a tag is bound to, a few levels; what the tags' graph is offered
-- from. The entries met on the way are kept in tag_entry too, for their names
CREATE TABLE tag_entry_above (
    catalogue VARCHAR(100) NOT NULL,
    entry     VARCHAR(100) NOT NULL,
    above     VARCHAR(100) NOT NULL,
    PRIMARY KEY (catalogue, entry, above)
);

-- When what the entry is a kind of was read: null - never
ALTER TABLE tag_entry ADD COLUMN above_read_at TIMESTAMP WITH TIME ZONE;
