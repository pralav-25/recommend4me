# A source's corrections: `corrections`

`sources/<source>/corrections.mv.db` — The user's word on the works of one source: values of facets confirmed, added or taken away, fields overridden, duplicates merged within the source. Backed up on every start.

| Table | What it holds |
|---|---|
| `facet_correction` | the user's word on a value of a facet of a work: added (true) or taken away (false) |
| `facet_complete` | the user said a work's values of a facet are all named |
| `field_correction` | a field of a work as the user wrote it: the title, a text, a number |
| `item_link` | two works the user said are one: of the source, or of it and another source of the content type — kept by the source of the smaller key |

The diagram is made of the migrations by `SchemaDocsTest`: change it with them.

```mermaid
erDiagram
    facet_complete {
        VARCHAR item_id PK
        VARCHAR facet PK
        TIMESTAMPTZ completed_at
    }
    facet_correction {
        VARCHAR item_id PK
        VARCHAR facet PK
        VARCHAR value_key PK
        BOOLEAN added
        VARCHAR name "null"
        TIMESTAMPTZ corrected_at
    }
    field_correction {
        VARCHAR item_id PK
        VARCHAR field PK
        CLOB content
        TIMESTAMPTZ corrected_at
    }
    item_link {
        VARCHAR item_id PK
        VARCHAR other_item_id PK
        TIMESTAMPTZ linked_at
        VARCHAR other_source PK
    }
```
