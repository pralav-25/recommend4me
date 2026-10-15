-- THE STATISTICS OF THE TAGS, counted in the background of every work of every source and kept:
-- the screen of the tags reads them, not every work's tags at every request.

-- Of every site's tag: its name on the site and how many works have it, graded and liked among them,
-- as last counted; a name of null — no work has it now
ALTER TABLE site_tag ADD COLUMN name VARCHAR(500);
ALTER TABLE site_tag ADD COLUMN works INT DEFAULT 0 NOT NULL;
ALTER TABLE site_tag ADD COLUMN graded INT DEFAULT 0 NOT NULL;
ALTER TABLE site_tag ADD COLUMN liked INT DEFAULT 0 NOT NULL;

-- Of every normalized tag: how many works have it — by a site's tag of it or by the user's word —
-- graded and liked among them, and the sources whose site's tags are bound to it
CREATE TABLE tag_stat (
    tag     BIGINT              NOT NULL PRIMARY KEY REFERENCES normalized_tag (id) ON DELETE CASCADE,
    works   INT                 NOT NULL,
    graded  INT                 NOT NULL,
    liked   INT                 NOT NULL,
    sources VARCHAR(100) ARRAY  NOT NULL
);
