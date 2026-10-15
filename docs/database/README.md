# The databases

H2, one file per level of data under the data folder ([architecture](../architecture.md#storage)).
The schema of every one, as its migrations (`app/src/main/resources/db/<kind>`) make it — the plugins' own tables left out.
`SchemaDocsTest` keeps the diagrams as the migrations make them.

| Database | File |
|---|---|
| [The application](app.md) | `app.mv.db` |
| [A source](source.md) | `sources/<source>/source.mv.db` |
| [A source's corrections](corrections.md) | `sources/<source>/corrections.mv.db` |
| [A source's ratings](ratings.md) | `sources/<source>/ratings.mv.db` |
| [A content type's model](model.md) | `types/<type>/model.mv.db` |
| [The dictionaries](dictionaries.md) | `dictionaries.mv.db` |
| [The user's preferences](preferences.md) | `preferences.mv.db` |
