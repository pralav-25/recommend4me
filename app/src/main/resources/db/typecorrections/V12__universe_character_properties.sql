-- The catalogue's statements on the characters of the dictionary the plugins ask for — a
-- character's tag on Archive of Our Own (P8419) — every value a row of its own
CREATE TABLE universe_character_property (
    catalogue    VARCHAR(100)  NOT NULL,
    universe_id  VARCHAR(100)  NOT NULL,
    character_id VARCHAR(100)  NOT NULL,
    property     VARCHAR(100)  NOT NULL,
    property_value VARCHAR(2000) NOT NULL,
    PRIMARY KEY (catalogue, universe_id, character_id, property, property_value),
    FOREIGN KEY (catalogue, universe_id, character_id) REFERENCES universe_character (catalogue, universe_id, character_id) ON DELETE CASCADE
);

-- The properties the universe's characters were asked for with, sorted, comma-separated: a universe
-- asked for others than the plugins want now is asked again
ALTER TABLE universe ADD COLUMN properties VARCHAR(1000);
