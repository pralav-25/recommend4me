-- A work of the source linked to a work of another source of the content type too — they were kept
-- in the type's own corrections, now gone: a link is kept by the source of the smaller of the two
-- keys ("<source>/<item>"), its item first. A link within the source has the source itself as the
-- other source
ALTER TABLE item_link ADD COLUMN other_source VARCHAR(100) DEFAULT '${source}' NOT NULL;
ALTER TABLE item_link ALTER COLUMN other_source DROP DEFAULT;
ALTER TABLE item_link DROP PRIMARY KEY;
ALTER TABLE item_link ADD PRIMARY KEY (item_id, other_source, other_item_id);
CREATE INDEX item_link_other ON item_link (other_source, other_item_id);
