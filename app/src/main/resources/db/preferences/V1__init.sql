-- THE USER'S PREFERENCES, one of them for every content type: what the user would rather see.

-- The name the user gives an entity of a catalogue — a universe, a character of Wikidata — over the
-- catalogue's: the "Severus Snape" the reader knows over the catalogue's translation; shown everywhere
-- in its place, read in the works' texts as one more name of it
CREATE TABLE catalogue_entity_rename (
    catalogue  VARCHAR(100)             NOT NULL,
    entity     VARCHAR(200)             NOT NULL,
    name       VARCHAR(1000)            NOT NULL,
    renamed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (catalogue, entity)
);
