# recommend4me — rules of the repository

## The marking of a work exists twice: keep both alike

The user answers on a work's values in two places with the same behaviour:

- the web interface — `frontend/src`;
- the browser extension, on the site's own page — `browser-extension/extension/page.js` (plain
  JavaScript, its words in `i18n.js`).

**Every change of how answering looks or behaves is made in both, in the same commit**, and the
commit message says so. A change only one of them can have (the extension has no screen of tags,
the web interface no site's page to mark up) is named as such in the commit message. Before
finishing such a change, go through the table below and check each row in both places.

**After a change of the extension, bump `version` in `browser-extension/extension/manifest.json`**
and tell the user to update the extension.

| What | Web interface | Extension (`page.js`) |
|---|---|---|
| A value as a chip, its look by its state | `correction/FacetCorrections.tsx` `FacetValue` | `valueChip` |
| The order of a facet's values and candidates: the server's (likeliest first, a tag above another before it), kept while the work is open | `facet/valueOrder.ts` | `stableOrder` |
| ✓ ✕ ↺ of a value; none of a tag the work has by a tag under it (`implied`) | `correction/FacetAnswers.tsx` | `answers` |
| The mark beside a value: chance, "by tag" (`draftedFrom`), ↑ (`implied`) | `facet/FacetValueMarks.tsx` | `answers` |
| "✓ all" — the unanswered values, not offered, not implied | `FacetCorrections.tsx` `FacetLine` | `facetBlock` |
| "…" — more candidates; busy only while its own press is | `FacetCorrections.tsx` `MoreCandidates` (`AsyncButton`) | `moreOf` |
| A value found by its name and added | `FacetCorrections.tsx` `SearchButton` + `AddFacetValue`; characters `universe/FanficSection.tsx` `Picker` | `addField` |
| "No other …" — shown with no value too; the unconfirmed go | `universe/FanficSection.tsx` `AllNamed` | `completeButton` |
| An answer: only written; shown once kept, never before; read anew after 2 s of quiet, the work once its chances are made | `correction/useCorrections.ts`, `suggestion/useSuggestions.ts` `suggestionsLater` | `act`, `loadLater`, `settledLater` |
| The suggestions of a facet | `suggestion/Suggestions.tsx` | `suggestedChip` |
| Grades and interest | `item/useRateItem.ts`, `item/pieces.tsx` | `grades`, `interest` |
| Marks of reviews and pictures | `review/Reviews.tsx`, `picture/Pictures.tsx` | `marks`, `reviews`, `pictures` |
| A list's cards: grade, prediction, interest | `item/ItemBoard.tsx`, `item/pieces.tsx` | `cards`, `fillBadge` |

## An area that reads itself again lets the user choose how often

Every part of the web interface that reads its data again by itself has, in its heading, the choice
of how often — `components/RefreshSelect.tsx` with `lib/useRefresh.ts` (off, 1 s … 1 min). The
choice is kept in the browser's localStorage under the area's own key (`<area>.refresh`), and the
query reads `refetchInterval: every || false`. A new area that reads itself again gets one too.

## A button shows only its own work

A button is busy (spinner, disabled) only while its own press is at work — `AsyncButton` in the web
interface, `act` with its `about` mark in the extension. Never by the state of a query: a list read
anew in the background is not the button's work.
