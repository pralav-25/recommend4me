-- The vectors of a normalized tag by a text encoder — the plugin it is of and its model — one of
-- every name of it: of its sites' tags and of the entries of a catalogue it is bound to. A tag is
-- alike a text, or another tag, by the best of its names, not by their mean: a synonym added finds
-- more, it does not blur the rest. Made in the background for the tags that have none, and dropped as
-- the tag's names change; an empty array: a tag of no name to encode, not asked again till they do
CREATE TABLE tag_vector (
    tag     BIGINT          NOT NULL REFERENCES normalized_tag (id) ON DELETE CASCADE,
    plugin  VARCHAR(200)    NOT NULL,
    model   VARCHAR(200)    NOT NULL,
    vectors VARBINARY ARRAY NOT NULL,
    PRIMARY KEY (tag, plugin, model)
);
