-- The name the user gives a universe or a character of the dictionary, over the catalogue's: shown
-- everywhere in its place, and read in the works' texts as one more name of it. The catalogue's
-- name stays kept beside it, asked again with the universe
CREATE TABLE universe_local_name (
    catalogue VARCHAR(100)             NOT NULL,
    entry_id  VARCHAR(200)             NOT NULL,
    name      VARCHAR(1000)            NOT NULL,
    named_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (catalogue, entry_id)
);
