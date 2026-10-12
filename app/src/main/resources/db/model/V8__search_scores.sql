-- What the reranker said of a work for a line searched, kept with the hash of the text it read:
-- the pages of a search after the first, and the same line searched again, read none again; a work
-- whose text changed is read again
CREATE TABLE search_score (
    reranker   VARCHAR(100) NOT NULL,
    query      VARCHAR(500) NOT NULL,
    source     VARCHAR(100) NOT NULL,
    item_id    VARCHAR(200) NOT NULL,
    texts_hash BIGINT       NOT NULL,
    score      REAL         NOT NULL,
    PRIMARY KEY (reranker, query, source, item_id)
);
