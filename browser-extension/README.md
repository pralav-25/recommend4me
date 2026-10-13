# The Firefox extension

The browser tracking mode of recommend4me: every page you open whose address one of the
application's sources wants — a work on author.today, a fanfic on ficbook.net or on Archive of Our
Own, a thread on f95zone
— is sent to the application as you see it, after the page's scripts have filled it in. The
application tells the extension which addresses it wants (`GET /api/capture/patterns`); no other
page leaves the browser.

**The site as the application's interface.** On the same pages the extension shows what the
application knows of the work: a panel after the work's header with the prediction, the grade
buttons, what the prediction rests on and the suggested tags; beside every tag of the site the
model's confidence in brackets (in amber when the work more likely has it not) and your ✓ (it
has it) and ✕ (it has it not), pressed again to take the answer back; the tags you added and the
suggested ones (dashed, ✓ / ✕) after them, a field to add a tag; on ficbook and Archive of Our
Own, the worked out universes, characters and pairings in lines of their own under the site's; "+ / −" under every review and
picture of the work; on a list, the prediction and your grade on every card. Where these go each
source says itself (`GET /api/pages`); the switch is on the options page. What the extension puts
into a page is never sent back with it.

A page is sent when it has loaded and again when its content grows while you are on it (a reader
loads the text of a chapter after the page itself), at most once every few seconds and not after
a minute. The toolbar badge says how many works the page gave (`✓` — none new, `!` — the
application did not answer). The popup shows whether the application answers, the last pages sent,
a switch, and a button to send the open page whatever the patterns say.

## Installing

A stock Firefox keeps only signed extensions, so the extension is loaded as **a temporary
add-on**, from its `manifest.json` on the disk: the application's distribution has it in
`extension/` (when run from this repository, `browser-extension/extension/`). The application's
"Слежение в браузере" dialog shows the full path with a button to copy it. Open
`about:debugging#/runtime/this-firefox`, press "Load Temporary Add-on…" and paste the path into
the file name field. It lasts until Firefox is closed; after the application is updated, press
"Reload" by the extension on the same page.

The address of the application (by default `http://127.0.0.1:8095`) is set on the extension's
options page.

## Languages

The extension speaks English, Russian and Serbian (Latin script): the language chosen on the
options page, else the first of the browser's languages it has, else English (the userscript —
the browser's). Its own words are in `extension/i18n.js`, the same keys in every language. The
application sends no words, only the keys of its words (`"<namespace>:<key>"`); the content script
reads the words of the namespaces it meets once for the page's life, `/i18n/<namespace>/<language>.json`
of the application. The description in the manifest is in `extension/_locales`.

## Development

```bash
npm install
```

```bash
npm run lint
```

`npm run dev` starts a Firefox with the extension loaded and reloads it on every change.
