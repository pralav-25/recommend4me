-- What a site says a value of a facet means (a tag's page describes the tag): by its key, as the site
-- wrote it last
CREATE TABLE facet_value_description (
    facet       VARCHAR(100)  NOT NULL,
    value_key   VARCHAR(500)  NOT NULL,
    description VARCHAR(4000) NOT NULL,
    PRIMARY KEY (facet, value_key)
);
