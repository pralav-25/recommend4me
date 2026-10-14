-- The hash of a work's text every pair of it was last read at by a reranker, and whether it was a
-- graded work then: a pass reads only the pairs of the works whose text or grading changed since,
-- not every pair of the type. Written when a pass has read them all.
CREATE TABLE work_likeness_text (
    reader    VARCHAR(100) NOT NULL,
    source    VARCHAR(100) NOT NULL,
    item_id   VARCHAR(200) NOT NULL,
    text_hash BIGINT       NOT NULL,
    graded    BOOLEAN      NOT NULL,
    PRIMARY KEY (reader, source, item_id)
);

-- The pairs of a work as the one read against the graded works: those of a new or changed work,
-- and those a removed work leaves, of every reader
CREATE INDEX work_likeness_item ON work_likeness (source, item_id, reader);
