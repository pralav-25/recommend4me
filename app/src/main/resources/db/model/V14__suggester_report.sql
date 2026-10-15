-- What the fitting of a facet's suggester tried and chose, kept for the page of the models: the
-- report as JSON (the settings tried, the loss each held out, the one chosen), how long the fitting
-- took, how many items and values it learnt from. Of a state fitted before, none till it is fitted again
ALTER TABLE facet_suggester ADD COLUMN report CLOB;
ALTER TABLE facet_suggester ADD COLUMN fit_millis BIGINT;
ALTER TABLE facet_suggester ADD COLUMN items INT;
ALTER TABLE facet_suggester ADD COLUMN value_count INT;
