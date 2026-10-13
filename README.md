# recommend4me

A local recommender of works — games, books, fanfics — collected from sites by plugins. You grade
some works with one of five grades; a model learns from them which works you like more than others
and puts everything not graded yet in that order, on a 0..10 scale of your own.

| Grade | Meaning |
|---|---|
| 1 | do not like it |
| 2 | can be played / read |
| 3 | liked it on the whole |
| 4 | very good |
| 5 | give me more |

Everything is local: the databases, the pictures, the neural encoders and the model live on your
machine, in `%LOCALAPPDATA%\recommend4me` (`~/.local/share/recommend4me` elsewhere).

## Plugins

Every site, every encoder, every way of turning vectors into scores and the search is a plugin — a
folder of jars in `plugins/`:

| Plugin | What it does |
|---|---|
| `source-ao3` | [Archive of Our Own](https://archiveofourown.org) fan works in every language, browser tracking and a work's page on request |
| `source-author-today` | [author.today](https://author.today) books, browser tracking only |
| `source-ficbook` | [ficbook.net](https://ficbook.net) fanfics and originals, browser tracking only |
| `encoder-bge-m3` | texts → vectors by bge-m3 (any language, one space, 1024 numbers); the one used when its files are there |
| `encoder-e5` | texts → vectors by multilingual-e5-small: the one used without bge-m3's files |
| `encoder-siglip2` | pictures → vectors by SigLIP2 NaFlex, proportions kept |
| `reranker-bge` | bge-reranker-v2-m3 reads a work's description together with every graded work's: the likeness the model reads |
| `scorer-pairwise` | pairwise logistic ranking (RankNet with a line), the default |
| `scorer-knn` | the k nearest graded works by cosine, a baseline |
| `scorer-fm` | a pairwise factorization machine: the line and every two features meeting |
| `scorer-kernel` | pairwise kernel ranking with the arc-cosine kernel: likeness to the graded works |
| `scorer-boosting` | gradient boosted trees on the pairwise logistic loss |
| `search-lucene` | search by words (English and Russian forms) and by meaning |
| `catalogue-wikidata` | the universes of fan fiction and their characters from Wikidata, asked only when you press the button |
| `suggester-bilinear` | one model of every work and every value: the chance of every tag, universe, character and pairing of a work from all it is — its title, texts, reviews, chapters, pictures and the site's own fields |

A source has up to three modes: **scrape all** (the whole catalogue, resumable), **updates** (what
is new since the last time) and **browser tracking**: the pages you open in Firefox are sent to the
application by its extension ([`browser-extension`](browser-extension/README.md)) and read there —
the application requests nothing from such a site itself. The same extension makes the site an
interface of the application: on a work's page it shows the prediction, grades the work, corrects
its tags in place, offers the suggested ones and marks its reviews and pictures; on a list it
shows the prediction of every card.

How it fits together — storage by level and file, the model, the plugin contract — is in
[docs/architecture.md](docs/architecture.md).

## Data

Every source has three database files (H2): what the site says, with the vectors made of it;
your corrections (tags added and removed, fields overridden, duplicates merged); your own data
(grades, marks on pictures and reviews, your actions seen on the site). Every content type has a
file of what is learnt — the model and the predictions — which can always be made again. Your own
files are backed up on every start into `backups/`.

The search, the list and the training read every file of a content type in batch; nothing is kept
in memory between requests.

## Running

Requirements: JDK 25, Node 24 (for the build).

```bash
./gradlew installDist
```

```bash
app/build/install/recommend4me/bin/recommend4me
```

Then open http://localhost:8095. On Windows the script is `recommend4me.bat`. With another plugin
folder beside the installed ones (a plugin of another repository):

```bash
./gradlew :app:run -PpluginDirs=../recommend4me-f95zone/plugin/build/plugin
```

### Neural encoders

The encoders' files go into the `models` folder of the data folder (not into git). The text encoder,
bge-m3 (1.1 GB, in half precision; fast on a card, slow on the CPU):

```bash
curl -L -o "$LOCALAPPDATA/recommend4me/models/bge-m3-fp16.onnx" https://huggingface.co/Xenova/bge-m3/resolve/main/onnx/model_fp16.onnx
```

```bash
curl -L -o "$LOCALAPPDATA/recommend4me/models/bge-m3-tokenizer.json" https://huggingface.co/Xenova/bge-m3/resolve/main/tokenizer.json
```

Without it, multilingual-e5-small (small and fast, weaker by far):

```bash
curl -L -o "$LOCALAPPDATA/recommend4me/models/e5-small.onnx" https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/model.onnx
```

```bash
curl -L -o "$LOCALAPPDATA/recommend4me/models/e5-small-tokenizer.json" https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/tokenizer.json
```

The reranker (1.1 GB, in half precision; fast on a card, slow on the CPU):

```bash
curl -L -o "$LOCALAPPDATA/recommend4me/models/bge-reranker-v2-m3-fp16.onnx" https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/main/onnx/model_fp16.onnx
```

```bash
curl -L -o "$LOCALAPPDATA/recommend4me/models/bge-reranker-v2-m3-tokenizer.json" https://huggingface.co/onnx-community/bge-reranker-v2-m3-ONNX/resolve/main/tokenizer.json
```

SigLIP2 is exported by [`plugins/encoder-siglip2/tools/export_siglip2_naflex.py`](plugins/encoder-siglip2/tools/export_siglip2_naflex.py).
Without them the model reads no texts or pictures; without the reranker, no likeness of the descriptions to the graded works. With `-Drecommend4me.gpu=true` the encoders run
on an NVIDIA card; [`tools/fetch-cuda.ps1`](tools/fetch-cuda.ps1) fetches the CUDA libraries.

### Access from other devices

By default the application is this machine's only. A personal `application.yaml` in the data
folder opens it to networks you trust — say, a tailnet:

```yaml
recommend4me:
  access:
    networks: [100.64.0.0/10, "fd7a:115c:a1e0::/48"]
    hosts: [".ts.net"]
```

It then listens on this machine's addresses inside those networks too, and answers names ending
in `.ts.net`. Every write must come from its own page or from the browser extension.

## Development

```bash
./gradlew build
```

The backend is Kotlin, Spring Boot 4, jOOQ over H2 with Flyway, ONNX Runtime and OpenBLAS; the
frontend React 19 with TypeScript, Vite, Tailwind and shadcn/ui, built into the application. The
HTTP contract is [api/openapi.yaml](api/openapi.yaml); both the Spring interfaces and the
TypeScript types are generated from it.

`npm run dev` in `frontend/` serves the interface on :5173 against a running application.
