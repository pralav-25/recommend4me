-- Whether the user finds a work interesting to read at all, apart from how good it is: a work the
-- model would grade 10 may still be of no interest. One mark an item, whatever its version
CREATE TABLE interest (
    item_id     VARCHAR(200)             PRIMARY KEY,
    interesting BOOLEAN                  NOT NULL,
    marked_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
