-- The tags and the universes moved to the dictionaries common to every content type (dictionaries
-- V1), copied there before this ran: what is left of a type is the links across its sources and the
-- judgments of its search
DROP TABLE normalized_tag_entry CASCADE;
DROP TABLE normalized_tag_merge CASCADE;
DROP TABLE normalized_tag_distinct CASCADE;
DROP TABLE normalized_tag_parent CASCADE;
DROP TABLE site_tag CASCADE;
DROP TABLE normalized_tag CASCADE;
DROP TABLE tag_entry_above CASCADE;
DROP TABLE tag_entry_text CASCADE;
DROP TABLE tag_entry CASCADE;
DROP TABLE universe_character_property CASCADE;
DROP TABLE universe_character_class CASCADE;
DROP TABLE universe_character_choice CASCADE;
DROP TABLE universe_class_choice CASCADE;
DROP TABLE universe_character CASCADE;
DROP TABLE universe_property CASCADE;
DROP TABLE universe_class CASCADE;
DROP TABLE universe_local_name CASCADE;
DROP TABLE universe CASCADE;
