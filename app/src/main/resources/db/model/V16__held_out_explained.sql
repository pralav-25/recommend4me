-- What a model that had not seen a graded work says of it — its score, where the user's grade stands,
-- what moves the score — worked out in the queue of the computations once the work is opened: a
-- request never waits for a computation (user, 2026-10-16). Of a work with too few works for folds,
-- the score null: none to work out. Gone with every training, and with a word on the work.
CREATE TABLE held_out_explained (
    source      VARCHAR(100)     NOT NULL,
    item_id     VARCHAR(200)     NOT NULL,
    score       DOUBLE PRECISION,
    grade_place DOUBLE PRECISION,
    explanation CLOB,
    PRIMARY KEY (source, item_id)
);
