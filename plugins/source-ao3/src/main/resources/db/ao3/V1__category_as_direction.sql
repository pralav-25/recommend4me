-- The category a standard direction, shared with ficbook's (Books.DIRECTION): the values of the works
-- whose pages are not kept to be read again given the standard keys; "No category" is none
UPDATE item_facet SET value_key = CASE value_key
    WHEN 'Gen' THEN 'gen'
    WHEN 'F/M' THEN 'het'
    WHEN 'M/M' THEN 'slash'
    WHEN 'F/F' THEN 'femslash'
    WHEN 'Multi' THEN 'mixed'
    WHEN 'Other' THEN 'other'
END
WHERE facet = 'category' AND value_key IN ('Gen', 'F/M', 'M/M', 'F/F', 'Multi', 'Other');

DELETE FROM item_facet WHERE facet = 'category' AND value_key NOT IN ('gen', 'het', 'slash', 'femslash', 'mixed', 'other');

DELETE FROM facet_value WHERE facet = 'category' AND value_key NOT IN ('gen', 'het', 'slash', 'femslash', 'mixed', 'other');

MERGE INTO facet_value (facet, value_key, name) KEY (facet, value_key) VALUES
    ('category', 'gen', 'Джен'),
    ('category', 'het', 'Гет'),
    ('category', 'slash', 'Слэш'),
    ('category', 'femslash', 'Фемслэш'),
    ('category', 'mixed', 'Смешанная'),
    ('category', 'other', 'Другие виды отношений');
