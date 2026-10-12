-- The chance, 0..1, that the user finds the item interesting, by the model of interest; none
-- without one. Written with the score, by the same training
ALTER TABLE prediction ADD COLUMN interest DOUBLE PRECISION;
