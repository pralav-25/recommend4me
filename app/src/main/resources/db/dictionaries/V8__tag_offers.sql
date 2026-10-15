-- The offers of the tags' graph as last worked out — with the names and the works of the tags, in
-- the queue of the computations: they want the graded works of every tag, a pass over every work —
-- as JSON, one row. A request reads them as kept, never waits for them (user, 2026-10-16).
CREATE TABLE tag_offers (
    id      INT                      NOT NULL PRIMARY KEY,
    made_at TIMESTAMP WITH TIME ZONE NOT NULL,
    offers  CLOB                     NOT NULL
);
