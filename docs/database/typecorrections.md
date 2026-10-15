# A content type's corrections: `typecorrections`

`types/<type>/corrections.mv.db` — The user's word across the sources of one content type. Backed up on every start.

| Table | What it holds |
|---|---|
| `item_link` | two works of two sources of the type the user said are one |
| `search_judgment` | the user's word on whether a work found by a line searched is relevant |

The diagram is made of the migrations by `SchemaDocsTest`: change it with them.

```mermaid
erDiagram
    item_link {
        VARCHAR source PK
        VARCHAR item_id PK
        VARCHAR other_source PK
        VARCHAR other_item_id PK
        TIMESTAMPTZ linked_at
    }
    search_judgment {
        VARCHAR query PK
        VARCHAR source PK
        VARCHAR item_id PK
        BOOLEAN relevant
        TIMESTAMPTZ judged_at
    }
```
