-- What every normalized tag of the type without a name of the user's is shown by, worked out of the
-- names of its works' tags on the sites: the most used among the works liked, the graded, all of
-- them, the first by the alphabet ([rule] says which). Made again as the works and their grades change
CREATE TABLE tag_shown_name (
    tag  BIGINT        PRIMARY KEY,
    name VARCHAR(1000) NOT NULL,
    rule VARCHAR(20)   NOT NULL
);
