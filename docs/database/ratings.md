# A source's ratings: `ratings`

`sources/<source>/ratings.mv.db` — The user's own judgments of the works of one source. Backed up on every start.

| Table | What it holds |
|---|---|
| `rating` | the user's grade of a version of a work |
| `interest` | the user's mark of interest of a work |
| `picture_mark` | the user's mark of a picture |
| `review_mark` | the user's mark of a review |
| `match_feedback` | the user's word on a match the application found for a mark |
| `site_signal` | the user's own action seen on the site: read, liked, on a shelf — shown, never trained on |

The diagram is made of the migrations by `SchemaDocsTest`: change it with them.

```mermaid
erDiagram
    interest {
        VARCHAR item_id PK
        BOOLEAN interesting
        TIMESTAMPTZ marked_at
    }
    match_feedback {
        VARCHAR mark_kind PK
        VARCHAR mark_item PK
        VARCHAR mark_ref PK
        VARCHAR match_source PK
        VARCHAR match_item PK
        VARCHAR match_ref PK
        SMALLINT verdict
        TIMESTAMPTZ given_at
    }
    picture_mark {
        VARCHAR item_id PK
        INTEGER position PK
        VARCHAR url
        SMALLINT mark
        TIMESTAMPTZ marked_at
    }
    rating {
        VARCHAR item_id PK
        VARCHAR version PK
        INTEGER grade
        TIMESTAMPTZ rated_at
    }
    review_mark {
        VARCHAR item_id PK
        VARCHAR review_id PK
        SMALLINT mark
        TIMESTAMPTZ marked_at
    }
    site_signal {
        VARCHAR item_id PK
        VARCHAR signal PK
        VARCHAR content
        TIMESTAMPTZ seen_at
    }
```
