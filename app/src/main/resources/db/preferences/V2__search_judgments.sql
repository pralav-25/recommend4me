-- The user's word on what a search found: whether a work answers a line searched for, the line as it
-- is searched (trimmed, in lower case). What the quality of the search is measured by, a search
-- against another; never learnt from by the model. The work's source tells its content type
CREATE TABLE search_judgment (
    query     VARCHAR(500)             NOT NULL,
    source    VARCHAR(100)             NOT NULL,
    item_id   VARCHAR(200)             NOT NULL,
    relevant  BOOLEAN                  NOT NULL,
    judged_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (query, source, item_id)
);
