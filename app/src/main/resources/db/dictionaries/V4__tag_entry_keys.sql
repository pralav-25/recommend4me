-- The references to the entries kept, made keys: a tag is bound to an entry kept — kept in the same
-- transaction before it is bound — and what an entry is a kind of is kept of an entry kept, linked to
-- entries kept: the entries above are kept before the links to them, and one the catalogue does not
-- give (gone from it) is no link
ALTER TABLE normalized_tag_entry ADD FOREIGN KEY (catalogue, entry) REFERENCES tag_entry (catalogue, entry);

ALTER TABLE tag_entry_above ADD FOREIGN KEY (catalogue, entry) REFERENCES tag_entry (catalogue, entry) ON DELETE CASCADE;

ALTER TABLE tag_entry_above ADD FOREIGN KEY (catalogue, above) REFERENCES tag_entry (catalogue, entry) ON DELETE CASCADE;
