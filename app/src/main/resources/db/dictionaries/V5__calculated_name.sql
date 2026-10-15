-- The name the application works a tag out to — the label of the entry it is bound to, else the name
-- of its sites' tags most used among the works liked, graded, all, the first by the alphabet
-- (calculated_rule says which) — kept with the tag's statistics: the same pass counts them. The name
-- the user gives it stays the tag's own (normalized_tag.name) and is shown over it. A tag new has the
-- name of the site's tag it was made for till the pass, its works none yet
ALTER TABLE tag_stat ALTER COLUMN works SET DEFAULT 0;
ALTER TABLE tag_stat ALTER COLUMN graded SET DEFAULT 0;
ALTER TABLE tag_stat ALTER COLUMN liked SET DEFAULT 0;
ALTER TABLE tag_stat ALTER COLUMN sources SET DEFAULT ARRAY[];
ALTER TABLE tag_stat ADD COLUMN calculated_name VARCHAR(1000);
ALTER TABLE tag_stat ADD COLUMN calculated_rule VARCHAR(20);

-- The names worked out kept as they were; of a tag the user named, worked out again by the next pass
UPDATE tag_stat s SET
    calculated_name = (SELECT n.name FROM tag_shown_name n WHERE n.tag = s.tag AND n.rule <> 'EXPLICIT'),
    calculated_rule = (SELECT n.rule FROM tag_shown_name n WHERE n.tag = s.tag AND n.rule <> 'EXPLICIT');

INSERT INTO tag_stat (tag, calculated_name, calculated_rule)
SELECT n.tag, n.name, n.rule FROM tag_shown_name n
WHERE n.rule <> 'EXPLICIT'
  AND NOT EXISTS (SELECT 1 FROM tag_stat s WHERE s.tag = n.tag)
  AND EXISTS (SELECT 1 FROM normalized_tag t WHERE t.id = n.tag);

DROP TABLE tag_shown_name;
