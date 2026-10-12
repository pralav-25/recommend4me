-- The vector of every window of a text of an item longer than one window of the encoder: what the
-- search finds a piece of a long description by, where the text's one vector — the windows'
-- mean — is all of it at once. A text of one window has its text_vector alone
CREATE TABLE text_window (
    item_id   VARCHAR(200)   NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    text_key  VARCHAR(100)   NOT NULL,
    window_no INT            NOT NULL,
    vec       BINARY VARYING NOT NULL,
    PRIMARY KEY (item_id, text_key, window_no)
);

-- How many windows the encoder makes of the text, counted when its windows were made; null: not yet
ALTER TABLE text_vector ADD COLUMN windows INT;
