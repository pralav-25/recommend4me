-- The warnings among the site's tags, before the others, by their names in lower case as every tag's
-- key: of the works whose pages are not kept to be read again. The user may ignore them as any tag
MERGE INTO facet_value (facet, value_key, name) KEY (facet, value_key)
SELECT 'tag', LOWER(value_key), value_key FROM item_facet WHERE facet = 'warning' GROUP BY value_key;

INSERT INTO item_facet (item_id, facet, value_key, position)
SELECT w.item_id, 'tag', LOWER(w.value_key), w.position - 1000 FROM item_facet w
WHERE w.facet = 'warning'
  AND NOT EXISTS (SELECT 1 FROM item_facet t WHERE t.item_id = w.item_id AND t.facet = 'tag' AND t.value_key = LOWER(w.value_key));

DELETE FROM item_facet WHERE facet = 'warning';

DELETE FROM facet_value WHERE facet = 'warning';
