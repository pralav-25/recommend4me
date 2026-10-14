-- A merge of tags whose answers are still to be moved: the user's answers live in every source's
-- database of corrections, which no transaction of this one spans. Written with the merge in one
-- transaction, removed once every source's answers on the tag merged are the kept one's; what a
-- stop or a failure left is moved on the next start
CREATE TABLE normalized_tag_merge (
    from_tag  BIGINT                   NOT NULL PRIMARY KEY,
    into_tag  BIGINT                   NOT NULL,
    merged_at TIMESTAMP WITH TIME ZONE NOT NULL
);
