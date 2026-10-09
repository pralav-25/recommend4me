-- How much the description of a work answers that of a work the user graded, by a reranker
-- reading the two together: its own score, kept with the hash of both texts — a pair is read
-- again only when either text changes. Made in the background as works and grades come.
CREATE TABLE work_likeness (
    reader        VARCHAR(100) NOT NULL,
    graded_source VARCHAR(100) NOT NULL,
    graded_id     VARCHAR(200) NOT NULL,
    source        VARCHAR(100) NOT NULL,
    item_id       VARCHAR(200) NOT NULL,
    texts_hash    BIGINT       NOT NULL,
    score         REAL         NOT NULL,
    PRIMARY KEY (reader, graded_source, graded_id, source, item_id)
);
