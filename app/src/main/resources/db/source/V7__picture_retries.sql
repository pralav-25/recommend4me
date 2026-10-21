-- A picture whose download failed is tried again later and later: the failures of it in a row, and
-- when it is tried next — none of a picture that cannot be decoded or is gone (404), never tried again
-- until its address changes
ALTER TABLE picture ADD COLUMN failures INT DEFAULT 0 NOT NULL;
ALTER TABLE picture ADD COLUMN retry_at TIMESTAMP WITH TIME ZONE;

-- Of a failure that may pass, kept before: one failure, tried again at once
UPDATE picture SET failures = 1, retry_at = CURRENT_TIMESTAMP
WHERE error IS NOT NULL AND error NOT LIKE 'cannot decode%' AND error NOT LIKE 'HTTP 404%';
