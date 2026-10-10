-- The works the user removed from the library — gone from the site, never to come back: a page
-- read again (a list kept from before, the parser changed) does not bring them back
CREATE TABLE removed_item (
    item_id    VARCHAR(200)             PRIMARY KEY,
    removed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
