-- The direction a standard one (Books.DIRECTION): the site's keys are its keys already; "unknown" —
-- the site does not tell — is none, of the works whose pages are not kept to be read again
DELETE FROM item_facet WHERE facet = 'direction' AND value_key NOT IN ('gen', 'het', 'slash', 'femslash', 'mixed', 'other', 'article');

DELETE FROM facet_value WHERE facet = 'direction' AND value_key NOT IN ('gen', 'het', 'slash', 'femslash', 'mixed', 'other', 'article');
