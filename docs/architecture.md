# Architecture

recommend4me is a local recommender: it collects works (games, books…) from sites, the user
grades some of them, and a model trained on those grades puts every other work in order on the
user's own 0..10 scale. The application knows nothing of any particular site: every site, every
neural encoder, every way of turning vectors into scores and the search are plugins.

## Modules

| Module | What it is |
|---|---|
| `plugin-api` | The contract between the application and its plugins: interfaces and data types only. |
| `libs/matrix` | Off-heap float arrays, OpenBLAS products, the Vector API loops. Used by the core and by scorers. |
| `libs/optim` | L-BFGS in ask/tell form, shared by the scorers. |
| `libs/onnx` | ONNX Runtime sessions on the CPU or CUDA, shared by the encoder plugins. |
| `app` | The application: storage, background work, training, the HTTP API, the web interface, the plugin loader. |
| `frontend` | The web interface (React), built into the application's static resources. |
| `plugins/encoder-bge-m3` | Text encoder: bge-m3, the one used when its files are there. |
| `plugins/encoder-e5` | Text encoder: multilingual-e5-small, without bge-m3's files. |
| `plugins/encoder-siglip2` | Picture encoder: SigLIP2 NaFlex. |
| `plugins/reranker-bge` | Reranker: bge-reranker-v2-m3 reads two texts together: the line searched and every work found. |
| `plugins/scorer-pairwise` | Pairwise logistic ranking (RankNet with a line), C chosen by cross-validation. |
| `plugins/scorer-knn` | k nearest rated neighbours by cosine — a baseline. |
| `plugins/scorer-fm` | Pairwise factorization machine: the line and every two features meeting through vectors of rank 8; C chosen by cross-validation. |
| `plugins/scorer-kernel` | Pairwise kernel ranking with the arc-cosine kernel (no width to choose); C chosen by cross-validation. |
| `plugins/scorer-boosting` | Gradient boosted trees on the pairwise logistic loss, Newton leaves; the number of trees chosen by cross-validation. |
| `plugins/search-lucene` | Search by words and by meaning over one Lucene index per content type. |
| `plugins/catalogue-wikidata` | The universes of fan fiction and their characters, from Wikidata. |
| `plugins/suggester-bilinear` | The values a work should have — tags, universes, characters, pairings — by one model of every work and every value. |
| `plugins/source-ao3` | archiveofourown.org, browser tracking and a work's page on request. |
| `plugins/source-author-today` | author.today, browser tracking only. |
| `plugins/source-ficbook` | ficbook.net, browser tracking only. |
| `browser-extension` | The Firefox extension of the browser tracking mode. |

The f95zone.to source lives in a private repository of its own, `recommend4me-f95zone`, built
against this one as a Gradle composite build.

## Plugins at run time

The application is a distribution, not a fat jar: `lib/` holds the application and its libraries,
`plugins/<id>/` the jar of every plugin with the libraries it needs beyond the application's.
`Launcher` puts every jar of every plugin folder into one class loader above the application's —
one, so that two plugins sharing a library with native code (ONNX Runtime) load it once — and
starts Spring with it. A plugin is a Spring auto-configuration
(`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`): its beans
implement the interfaces of `plugin-api`, and the application finds them by type.

Plugin folders: `<install>/plugins`, plus any listed in `recommend4me.plugin-dirs`.

## Content types and sources

A source belongs to a content type (`games`, `books`). Works of one type are graded on one scale
and ranked by one model, whichever site they came from: a book of author.today and a fanfic of
ficbook are compared with each other.

Every source has up to four modes:

- **scrape all** — the whole catalogue, resumable;
- **updates** — what is new since the last time;
- **browser tracking** — the pages the user opens in Firefox are sent by the extension and parsed;
  nothing is requested from the site by the application;
- **page** — one work's page downloaded by the application when the user asks for it (the
  "refresh" button of a work, or a work graded that is known by its card alone); no job, no crawl.

author.today and ficbook have only browser tracking: their catalogues are never scraped whole.
Archive of Our Own has browser tracking and single pages, one request at a time, five seconds
apart, with "bot" in the user agent, as the site asks of anything automated.

## Storage

The tables of every database, as diagrams: [docs/database](database/README.md).

H2, one file per level of data, under the data folder (`%LOCALAPPDATA%\recommend4me` on
Windows, `~/.local/share/recommend4me` elsewhere; `recommend4me.data-dir`):

```
app.mv.db                          settings
dictionaries.mv.db                 the user's dictionaries, every content type's: the normalized
                                   tags of every site (merged, named, linked, bound to entries,
                                   ignored) and the universes with their characters
preferences.mv.db                  the user's preferences, every content type's: the names given
                                   to universes and characters, the judgments of the search
sources/<source>/source.mv.db      what the site says: works, facets, numbers, texts, pictures,
                                   reviews, text parts, captured pages — and their vectors, a
                                   compressed form of the same scrape
sources/<source>/corrections.mv.db the user's corrections: tags added and removed, fields
                                   overridden, duplicates merged within the source and with
                                   the other sources of its type (kept by the smaller key)
sources/<source>/ratings.mv.db     the user's own data: grades, marks on pictures and reviews,
                                   words on matches, the user's own actions seen on the site
sources/<source>/images/           picture files
types/<type>/model.mv.db           the trained model, the predictions, the likeness to the marks,
                                   the directions of the set embeddings, the suggested tags — all
                                   recomputable
types/<type>/search-index/         the search index
models/                            ONNX graphs of the encoders
backups/                           the settings, the dictionaries, the preferences, corrections
                                   and ratings, zipped on every start
```

Every file has its own connection pool and its own Flyway history. H2 does not join across
files, so whatever reads several of them — the list of works, filtered by corrected tags, sorted
by prediction, without the rated — reads each file once, in batch, only the columns it needs,
and puts the answer together in memory for that one request. Nothing is kept between requests.

## Works, items and their data

An *item* is a work as one source knows it, keyed by the source's own id. What the model and the
interface read of it is generic:

- **facets** — categorical values: tags, engine, status, developer, fandom, genre, language…
  (`facet`, `value_key`), with display names in `facet_value`. The source declares its facets:
  label, whether they filter the list, whether the model reads them.
- **numbers** — likes, views, votes, chapters…, declared with how the model reads them.
- **texts** — overview, annotation, changelog…, declared with their search weight, whether the
  model reads their vector and under which block key (two book sources share `text:annotation`).
- **pictures** — position 0 the cover, then screenshots; each analysed by the picture encoder.
- **reviews** — other readers' opinions: f95zone reviews, author.today рецензии, ficbook and
  Archive of Our Own comments; each encoded whole.
- **parts** — the text of a book's chapters, cut into windows; a vector for every window.

A source may keep tables of its own in its source database (its own Flyway history).

## Corrections

The interface and the model read *effective* data: the source's, with the user's corrections on
top — tags added and removed, fields overridden. Merged items form a cluster: one card, one
grade; the cluster's members never fall into different folds of cross-validation and are never
paired with each other.

## The model

For every content type:

1. one pass over every source of the type builds the input of every item: the vectors of its
   texts, pictures and sets, its facets, numbers, and the grade of an earlier version;
2. the active scorer (`recommend4me.scorer.<type>` setting, the first available by default) is
   measured on 5-fold cross-validation over works for each of its candidate parameters and the
   one-standard-error choice is made;
3. the grades are placed on 0..10 from the out-of-fold scores (the ladder);
4. every item is scored; the explanation switches feature groups off one at a time.

Scorers implement `Scorer`; anything that maps a row of features to a number fits.

## Tags worked out

Authors tag their works carelessly or not at all, and write their pairings and characters as
they please. For every facet a source marks `suggest` (the tags of author.today and ficbook) or
`infer` (ficbook's pairings and main characters) the model gives the chance of every value a
work has, and of every value it lacks that it more likely has than not:

- every value of the site is shown — none is hidden — with the chance in brackets, marked when
  the work more likely has it not;
- of a `suggest` facet, a value the work lacks and more likely has is a suggestion to confirm;
- of an `infer` facet, such a value is the work's at once (marked as worked out): the site's
  line of the values (`FacetDef.original`, kept as a text) is shown above, and is what the model
  learns from beside the description, the chapters and the other tags.

The user says of any value that the work has it (✓) or has it not (✕): a correction, applied over
any chance, seen at once by the model, the filters and the search.

The work is the `FacetSuggester` plugin's (`suggester-bilinear`). It is handed every item of the
source — every input the model of the grades reads, each a block of its own (the title, the
description, the notes, the site's lines, the reviews and the windows of the chapters as sets,
the pictures), its values with the user's corrections, the values of its other facets, the site's
own among them — and every value with the vector of its name as a query. One model scores every
pair of a work and a value: σ((xA)·(zB + e) + b + w·p) — the work's inputs and the value's name
and groups carried to a few coordinates where they meet, the value's own coordinates and bias,
and the pair's own features (the site gives the value as an example, the texts name it, the
common bias). It is learnt by the weighted logistic loss over every pair: a value the work has
weighs 1, one the user rejected 1, one it merely lacks less (the rule of succession over the
user's answers), one it may not have at all and every value of a work with none of the facet
nought. Every work is judged by a model that did not learn from it (five folds by the work's id),
so a value the site gave it that nothing else supports comes out unlikely, and an answer on a
work does not move its own chances. The rank and the penalty are chosen by the held-out loss —
the strongest penalty within one standard error of the least.

The facets the model works out — the kinds, the universes, the main and the secondary characters,
the pairings, the tags, the languages — are worked out IN A CHAIN, in the order of the schema: each
reads as its context the values the site and the user give of every other facet, and the values
the model gives of the facets before it, made in the same pass. A work's characters are those of
the universes given before them, its pairings pairs of the characters given before them. The
fitting and the reading follow the same chain, so a facet is judged on what it was fitted on.

ONLY THE MODEL OF EACH FACET IS KEPT, in the model database: its fitted state and the values it
chooses from. THE CHANCES OF THE WORKS ARE NOT KEPT (user, 2026-10-15): whoever reads works — a
page, a card, the extension, a filter over the catalogue, the search index, the training of the
grades — gets them made in memory at the reading, of those works alone, from their vectors and
values by the models kept, through the chain. Nothing waits for a refit and nothing is written.
The model of a facet is fitted again in the background, in the queue of the computations, whenever
anything it learns from changes; a refit tells which works' given values changed by scoring every
work by the model before and after it.

Of a work's chances those shown are made — of the values it has, rejected, given as examples, more
likely than not, and the few likeliest others offered — not every character of a work's universes.
The user choosing among every value a work may have (the candidates) gets every one at the asking.

## The normalized tags

The application's layer of tags ("Теги") is of normalized tags, one dictionary of every site of
every content type — a tag of a site of games and one of a site of books may be one — kept in the
dictionaries: a normalized tag is an id and the name the user gave it, if any (`normalized_tag`);
every tag of every site — its source and the site's own key of it — is bound to exactly one
(`site_tag`, keyed by the two). A work's values of the layer are the ids, written out. A site's tag
seen first is bound to the normalized tag the sites' tags of its name are bound to, any case —
"Магия" of ficbook and "магия" of author.today one tag — when there is exactly one such, else to a
new normalized tag of its own (`TagNormalization`, as the works are captured).

Tags that mean one thing under other names — "Angst" of Archive of Our Own and "Ангст" of ficbook —
are merged by the user on the screen of the tags; the application only offers the tags alike by
the meaning of their names (the sites' tags' names encoded as the search encodes short texts, a
tag the mean of its sites' tags' directions, alike by the cosine), of one site or of another, and
the user may say two tags are different (`normalized_tag_distinct`). Merging is binding: the
sites' tags of one normalized tag are bound to the other, the user's answers on it become answers
on the other — the newest standing of a work answered on both — its links go with it, and it is
gone. A site's tag taken out is bound to a new normalized tag of its own; the answers stay with the
tag they were given on.

The graph is of normalized tags, in a table of its own (`normalized_tag_parent`), and a tag may
have several parents: a work with the child has every parent — shown on its card as given by the
child — and a work the user said has not a parent has not its children either, which are then not
shown; the "has not" of a tag above wins over a "has" of one under it. The model of the grades
reads the works' tags so, and the suggester of the tags learns from the answers so
(`TagHierarchy`). No tag is put under one of its own descendants. On a tag's page every alike tag,
and every tag the user said something of to it, has four words to press and press again: the same
tag (merged), a tag under it, a tag above it, a different one; every site's tag bound to it is a
row of its own, merged, unpressed to take it out.

A tag is shown by the name the user gave it; else by the label of the entries of the catalogues the
user bound it to, in the user's first language one of them has it in, of the one bound first; else
by its sites' tags' name most used by the works the user liked (graded above the middle of the
scale), by the works graded, by all its works; else by the first by the alphabet.

The user binds a tag to entries of a catalogue of tags (`TagCatalogue`; `catalogue-wikidata`) —
"Quidditch" to Q305561 — found by a name, a Q-id or the entry's page; the tag's names are offered to
search by, and a tag bound to none is searched for by its name as it opens — what is found is only
offered. The tag is named in the user's language, and the entries' labels and aliases of every
language find it. A tag may be bound to several entries and an entry to several tags. The binding
is the user's word, kept with the merges in the dictionaries (`normalized_tag_entry`); the entries' texts as the catalogue gave them when bound (`tag_entry`). A
tag merged into another gives it its entries.

What a tag means is what its sites' tags' own pages say (`ItemStore.describeFacetValues`, kept in
the source's `facet_value_description`: ficbook's page of a tag), then what the descriptions of its
entries say — each shown with who says it.

What an entry is a kind of (`TagCatalogue.above`: Wikidata's P279 "subclass of" and P1074
"fictional analog of", never P31) is read up from every entry bound, five levels, when it is bound
and once on start for a binding made before; kept in `tag_entry_above`, the entries met on the way
in `tag_entry` with their names. A tag's card offers another tag above it when one of its entries
is a kind of the other's, and under it the other way round, with the way up — "минет → половой
акт" — unless the two are related already; only the user puts one under another. "Update from the
catalogue" on the card reads its entries and every level above them again, what was kept shown
meanwhile.

Under the list of tags the screen shows the offers of the tags' graph (`Tags.hierarchyOffers`),
read apart and again by its own button: a tag under another as above; two tags bound to one entry
merged; a new tag of an entry no tag is bound to, above the tags that meet there first — of the
entries both of two tags are a kind of, the nearest to the farther of the two; where a tag is bound
there already, that tag is offered instead. Each says how many graded works it changes — of a link
the child's graded works without the parent, which get it, and the ones said to have not the parent,
which have not the child either; of a merge, the smaller tag's; of a new tag, all of its tags'. The
nearest in the catalogue come first — "oral sex" right above two tags before "process" three levels
up — and of those alike near, the ones changing most.

The names are worked out again a few seconds after the works or
their grades change and kept with the tags' statistics in the dictionaries (`tag_stat.calculated_name`):
made of the rest, not the user's word. Every source reads them from there, as the universes' names
are read from their dictionary, never from a copy of its own. The names the user gives the universes
and the characters are the user's preferences (`preferences.mv.db`, `catalogue_entity_rename`).

## Universes

A content type may have universes (`ContentType.universes`: books) — works may be fan fiction of
someone else's world. One dictionary of the universes the user agreed on, each with its
characters, is kept in the dictionaries for every such type. A universe comes from a catalogue plugin
(`UniverseCatalogue`; `catalogue-wikidata`): a universe is a Wikidata item — a franchise, a series,
a work — and its characters whatever is said to be in it or in its parts, with every name and
alias in the user's languages (`recommend4me.languages`), the way the fans write them among them.
The catalogue is asked only when the user asks — a search by the button with the variants shown,
a universe added, its characters refreshed from the universes screen or a site's page — and
what it answered is kept.

Every work of the type has five facets of the application's own beside the source's
(`UniverseFacets`), their values kept as the user's corrections and worked out by the model as
any facet's:

- `kind` — fan fiction, realism (our world as it is), an alternative history or an original
  work, the last three of no universe: the site's when it says (ficbook by the fandom
  "originals", author.today by the genre "Фанфик" — `SourceSchema.kindFrom`, shown above),
  suggested otherwise;
- `universe` — the universes of the dictionary the work is fan fiction of, one or several:
  suggested from the work's fandom, tags and texts to a work not known to be original, to be
  confirmed;
- `characters` — its main characters: of the characters of its universes — confirmed, or given
  by the model more likely than not and not rejected, none but the confirmed once the user said
  there are no others — and the original characters (ОЖП, ОМП), every name of a character held against the line the site writes
  them in (`SourceSchema.universeLine`, shown above), the tags, the description and the chapters;
- `secondary-characters` — its secondary characters, worked out and learnt as the main ones;
- `pairings` — pairs of the work's main characters, one whatever their order.

A character is a work's main one or its secondary one, never both (`UniverseFacet.leftOut`): what
the work has of one facet is not given, offered or shown — even crossed out — in the other. The
user's word comes first: a character said to be secondary leaves the main ones, and one said to
be either is taken from the other answer. Of the model's word alone the main characters, worked
out first, come first. The original characters are many persons and may be in both.

The characters and the pairings are given to the work at once when more likely than not; the
site's own strings stay its facets as they are, for the model and as what the model reads. The
universes, the characters of either kind and the pairings can each be said to be all named: the
work keeps its own and is offered no other.
Taking a universe out of the dictionary takes its links with it.

### Statements of the catalogue and drafts

A source plugin names the catalogue's statements it wants of the universes and their characters
(`Source.catalogueProperties`: Archive of Our Own asks Wikidata for P8419, the entity's tag on
AO3). They are asked with every universe added or refreshed — of the universe, of the works,
series and franchises within it, and of every character — and kept in the dictionary
(`universe_property`, `universe_character_property`); a universe asked for other statements than
the plugins want now is asked again on start.

From them a source drafts values of the layer (`Source.draft`): AO3 matches its fandoms to
universes and the names of its relationship tags ("A/B", "A & B") to characters by the tag, a
romantic pair of two matched characters to a pairing; the application adds the universe of every
drafted character the dictionary has in one universe alone (`LayerDrafts`). A name matching
nothing, or several entries, drafts nothing. Every matched character is a main one: the user moves
it to the secondary ones.

The drafts are derived data, kept in the type's model database (`layer_draft`), neither the
site's nor the user's. They are made again when a work is captured or refreshed — before the model
reads it — and of every work of the type when the dictionary changes and on start; the works whose
drafts changed are read again. A draft is a value the work has (`Chance.draftedFrom`): shown with
the site's value it came from instead of a chance, found by the search, read by the model as a
value of the work. The user's answers come first — a draft taken away stays taken away, whatever
it is made of again. The model learns from a draft the user has not confirmed with less weight
(`FacetData.DRAFT_WEIGHT`, `SuggestionTask.draftWeight`): it is likely, but no one's word.

## Languages of the interface

The web interface speaks English, Russian and Serbian (Latin script), through i18next and
react-i18next (`frontend/src/i18n`). The language is the one the user chose in the menu (kept in
the browser's local storage), else the first of the browser's languages the interface has, else
English. Numbers, dates and durations are formatted in it (`i18n/format.ts`).

The words are split by the section of the application they belong to: every folder of the
frontend with words of its own keeps them beside its code, `<section>-i18n.<language>.json` —
`tag/tag-i18n.en.json`, `universe/universe-i18n.ru.json` — one i18next namespace a section; the
words every section shares are the namespace `common`, in `i18n/`. Keys are flat,
`"Component.purpose"`; a word of a count has a key of each plural form of its language
(`works_one`, `works_few`, `works_many`, `works_other`) and is asked with `{ count }`.

The keys are typed: the English files are the shape `t` is checked against (`i18n/i18next.d.ts`),
so a key that does not exist does not compile; and `i18n/resources.ts` checks every other language
against the English — the same keys, no more and no fewer, every word of a count in each plural
form of that language — so a translation missing or left over does not compile either. A new
section's files are registered there.

### The server sends keys, never words

The server words nothing for the user. Where the API has something to show that is not data, it
sends the key of its words — `labelKey`, `nameKey`, `messageKey`, a `Problem`'s `code` with its
`params` — and the interface translates it. A key is `"<namespace>:<key>"`, the key within the
namespace flat and without ":" (`I18nKeys` of the plugin API builds them):

- `standard` — the application's own words, every content type's alike. The web interface keeps
  them (`frontend/src/standard/standard-i18n.<language>.json`, typed as every section's) and lays
  them out for the browser extension too (`/i18n/standard/<language>.json`);
- a plugin's id — a source's (`ficbook`), a scorer's (`pairwise`), a catalogue's (`wikidata`) —
  the words of that plugin alone. The plugin carries them among its resources,
  `i18n/<id>.<language>.json`, and the application lays them out as they are,
  `/i18n/<id>/<language>.json`; the interfaces read the namespaces of the plugins they show. A
  plugin's test checks its files with the plugin API's fixture `Translations`: every language,
  exactly the same keys, and of a source every key it needs (`I18nKeys.requiredKeys`).

The keys of a plugin's namespace: `title`; of a facet, number, text, signal of its own (not a
standard one) `facet.<key>`, `number.<key>`, `text.<key>`, `signal.<key>`, a named value of a
signal `signal.<key>.value.<value>`; `facet.<key>.none` of a facet whose works without a value are a
line of the filter; `setting.<key>` and `setting.<key>.hint`; `reviews` and `parts` — what it calls
the reviews and the parts of a text it keeps; `job.<key>` — a message its jobs leave.

The keys of the standard namespace the server sends:

- `type.<type>.title`, `type.<type>.verb`, `type.<type>.grade.<1..5>` — of `books`, `games`;
- `facet.<key>` of every standard facet and of the application's layer — `author`, `status`,
  `tag`, `fandom`, `pairing`, `tags`, `kind`, `universe`, `characters`, `secondary-characters`,
  `pairings` — and `facet.<key>.value.<value>` of a standard value: `status` — `finished`,
  `in-progress`, `frozen`; `kind` — `fanfiction`, `realism`, `alternative-history`, `original`;
- `number.<key>` of every standard number — `parts`, `words`, `likes`, `views`;
- `signal.<key>` and `signal.<key>.value.<value>` of every standard signal — `read` and `liked`
  of the value `yes`; `shelf` of `reading`, `saved`, `finished`, `disliked`, `purchased`;
- `character.oc-female`, `character.oc-male` — the original characters;
- `text.title` — the title among the texts the search and the model read; `part.untitled` — a
  chapter without a title, `{{n}}` its number;
- `model.previous` (the grade of the previous version), `model.source`, `model.pictures`,
  `model.reviews`, `model.cover`, `model.screens`, `model.screensOneByOne`,
  `model.reviewsOneByOne`, `model.textFragments`, `model.likePictures`, `model.likeReviews` — the
  parts and blocks of the model's input;
- `error.<code>` — a request refused, its `params` named in the words: `noField` (field),
  `siteField` (field — the key of its label), `noValue`, `noSuchWork`, `joinTypes`, `joinSelf`,
  `badRange` (range), `noDownloads`, `siteSilent` (error), `refreshFailed`, `badGrade` (min, max),
  `badMark`, `badAnswer`, `noUniverses` (type), `notInCatalogue` (universe), `catalogueSilent`
  (error), `noSuchUniverse`, `noSuchType`, `noSiteTags`, `tagSelf`, `tagAbove`, `tagBelow`,
  `tagThrough`, `notInDictionary` (value), `tagImplied` (tag),
  `noSetting` (setting), `noScorer` (scorer), `accessRefused`, `noExtensionFolder`.

What the model and the search read keeps one language, Russian, whatever the user reads — the
names of the standard values kept with the works (`StandardValue.modelName`), the original
characters — their texts are encoded once; the user is shown them by their keys. The data itself —
the works' texts, the sites' tags, the catalogue's names — is shown as it is.

A number every site of a content type tells alike is a standard number (`StandardNumber`): the
chapters of a book (`Books.PARTS`); of any type the likes — likes, kudos —, the views — views,
hits — and the words (`StandardNumbers`). A source declares its own number as the standard one
under its own key; the sources' numbers of one standard are one filter, one sorting and one
feature of the model.

The list of works is sorted by one choice of every order alike: the prediction, the date of the
update, the date the application first saw the work, the surprise, and every number the list can
be sorted by — the standard ones first.

## The site as the interface

On the pages the sources want, the extension also shows the work as the application knows it,
so the site itself is an interface of the application (`GET /api/pages?url=`): a panel with the
prediction, the grade buttons, what the prediction rests on and the tags; on the site's own tag
elements the model's chance and the user's ✓ and ✕, the user's and the suggested tags after
them; the worked out pairings and characters in lines of their own under the site's; "+ / −" on
every review and picture of the work; the prediction and the grade on every card of a list
(`POST /api/pages/cards`). A source says where these go by `PageDecor` — CSS selectors of its
pages — and which work a page or a card's link is of by `Source.itemIdOf`. Everything is changed
through the same calls as the application's own interface.

## Browser tracking

The extension asks the application which addresses it wants (`GET /api/capture/patterns`) and
sends every such page the user opens — its rendered DOM, without what the extension itself put
into it — to `POST /api/capture`. The application
keeps the page (`captured_page`), hands it to the source whose patterns match, and re-parses kept
pages when a parser's version grows.
