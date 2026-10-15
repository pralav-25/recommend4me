-- The user's word that an entry of a catalogue is too general to be a tag above others: a tag of it
-- — new, or one of its name — is offered above the tags no more; the offer is shown as said when asked
CREATE TABLE tag_entry_too_general (
    catalogue VARCHAR(100)             NOT NULL,
    entry     VARCHAR(100)             NOT NULL,
    said_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (catalogue, entry),
    FOREIGN KEY (catalogue, entry) REFERENCES tag_entry (catalogue, entry)
);
