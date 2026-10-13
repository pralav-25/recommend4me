-- The catalogue's statements the plugins ask for of a universe and of the works, series and
-- franchises within it or set in it — the fandoms of Archive of Our Own (P8419) of every book of a
-- series — every value a row of its own
CREATE TABLE universe_property (
    catalogue      VARCHAR(100)  NOT NULL,
    universe_id    VARCHAR(100)  NOT NULL,
    property       VARCHAR(100)  NOT NULL,
    property_value VARCHAR(2000) NOT NULL,
    PRIMARY KEY (catalogue, universe_id, property, property_value),
    FOREIGN KEY (catalogue, universe_id) REFERENCES universe (catalogue, universe_id) ON DELETE CASCADE
);

-- Asked before the universes' own statements were: asked again
UPDATE universe SET properties = NULL;
