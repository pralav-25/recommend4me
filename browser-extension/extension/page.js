/*
 * THE SITE AS THE APPLICATION'S INTERFACE, put into the pages the application's sources want.
 *
 * On a work's page: a panel with the prediction, the grade, what the prediction rests on, the
 * suggested tags and a field to add one; on the site's own tags a button to take each away (and to
 * bring it back), the tags the user added after them, the suggested ones as dashed chips to
 * confirm or reject; a "+ / −" under every review and picture of the work. On a list: the
 * prediction and the grade on every card.
 *
 * What to mark up the source says (GET /api/pages: CSS selectors of its pages); everything is
 * changed through the application's own calls, made by the background script. Every element put
 * here carries data-r4m — the tracking leaves those out of the pages it sends.
 */
(() => {
  if (window.__recommend4mePage) {
    window.__recommend4mePage.load();
    return;
  }

  const api = async (method, path, body) => {
    const answer = await browser.runtime.sendMessage({ type: "api", method, path, body });
    if (answer && answer.ok) {
      // Every key of the server's words the answer has can be said at once
      await need(keysIn(answer.data));
      return answer.data;
    }
    throw new Error(await problemText(answer));
  };
  const enc = encodeURIComponent;

  // --- The server's words ---

  /**
   * The words of the server's keys ("<namespace>:<key>") by namespace — "standard" and the plugins'
   * — read once for the page's life: static files of the application (/i18n/<namespace>/<language>.json).
   * A namespace being read is its promise; one that could not be read is read again when next needed.
   */
  const serverWords = new Map();

  function readNamespace(namespace) {
    if (!serverWords.has(namespace)) {
      // Kept only while still wanted: the words of a language no longer spoken are dropped
      const done = (words) => {
        if (serverWords.get(namespace) !== reading) return;
        if (words) serverWords.set(namespace, words);
        else serverWords.delete(namespace);
      };
      const reading = browser.runtime.sendMessage({ type: "api", method: "GET", path: `/i18n/${enc(namespace)}/${r4mLanguage}.json` }).then(
        (answer) => done(answer && answer.ok && answer.data),
        () => done(null),
      );
      serverWords.set(namespace, reading);
    }
    return serverWords.get(namespace);
  }

  /** The namespace of a key, "<namespace>:<key>": what comes before its ":"; null when it is no key, or a language's ([LANGUAGE]). */
  function namespaceOf(key) {
    const match = typeof key === "string" && /^([\w-]+):[^\s:]+$/.exec(key);
    return match && match[1] !== LANGUAGE ? match[1] : null;
  }

  /** The namespace of a language by its ISO 639 code, "language:ru": named by the browser, no word file holds them. */
  const LANGUAGE = "language";

  /** A language named in the language spoken — "русский", "Russian" — its code when the browser knows it not. */
  function languageName(code) {
    try {
      const name = new Intl.DisplayNames([r4mLanguage || navigator.language], { type: "language" }).of(code);
      return name ? name.charAt(0).toLocaleUpperCase() + name.slice(1) : code;
    } catch (e) {
      return code;
    }
  }

  /** The words of a namespace when they are read; else undefined. */
  function wordsOf(namespace) {
    const words = serverWords.get(namespace);
    return words && !(words instanceof Promise) ? words : undefined;
  }

  /** Reads the namespaces of [keys] not read yet; "standard" always. */
  async function need(keys) {
    const namespaces = new Set(["standard", ...keys.map(namespaceOf).filter(Boolean)]);
    await Promise.all([...namespaces].filter((n) => !wordsOf(n)).map(readNamespace));
  }

  /** The fields of the API that hold keys of the server's words. */
  const KEY_FIELDS = ["labelKey", "nameKey", "valueKey", "code"];

  /** Every key of the server's words in an answer of the API, however deep. */
  function keysIn(data, out = []) {
    if (Array.isArray(data)) data.forEach((x) => keysIn(x, out));
    else if (data && typeof data === "object") {
      Object.entries(data).forEach(([field, value]) => {
        if (KEY_FIELDS.includes(field) && typeof value === "string") out.push(value);
        else keysIn(value, out);
      });
    }
    return out;
  }

  /** The words of the server's [key] in the language spoken, filled from [params]; a key whose words are not read is shown as it is. */
  function said(key, params) {
    if (typeof key === "string" && key.startsWith(`${LANGUAGE}:`)) return languageName(key.substring(LANGUAGE.length + 1));
    const words = wordsOf(namespaceOf(key));
    const text = words ? words[key.substring(key.indexOf(":") + 1)] : undefined;
    return text !== undefined ? fill(text, params) : key;
  }

  /** The name of a value as shown: of the application's own value, by its key; of any other, as the data has it. */
  const nameOf = (v) => (v.nameKey ? said(v.nameKey) : v.name);

  /** The label of a facet as shown: by its key; a facet the application gave no label is shown by its own key. */
  const labelOf = (facet) => (facet.labelKey ? said(facet.labelKey) : facet.facet);

  /**
   * Why a call failed, worded: a refusal by the key of its words and its params — a param that is
   * itself a key of the server's words (the label of a field) said too —, the application silent,
   * or else what the log has.
   */
  async function problemText(answer) {
    if (!answer) return t("app.noAnswer");
    if (answer.unreachable) return t("app.silent");
    const problem = answer.problem;
    if (!problem || !problem.code) return answer.error || t("app.noAnswer");
    const params = problem.params || {};
    await need([problem.code, ...Object.values(params)]);
    const worded = {};
    Object.entries(params).forEach(([name, value]) => {
      worded[name] = namespaceOf(value) ? said(value) : value;
    });
    return said(problem.code, worded);
  }

  /** The facet the application gives every work of a type with universes: the universes of fan fiction. */
  const UNIVERSE = "universe";

  /** The facets that say which values a work may have — its universes, their characters, the pairs of them — hinted by its candidates. */
  const CHOSEN = ["universe", "characters", "secondary-characters", "pairings"];

  /**
   * The facets whose values are entries of the dictionary alone — a universe of the catalogue, a
   * pair of its characters: one is chosen among the hints, never made of a name typed, which would
   * be a value no dictionary knows. A character may be named as the work names it.
   */
  const KEYS_ONLY = ["universe", "pairings"];

  /** How long a shown work is not read again for every new send of a growing page. */
  const RELOAD_AFTER = 15 * 1000;

  /** What the application said of this page; null until it answers. */
  let view = null;
  let server = "";
  /** Why the last change not about a value failed; kept until the next change. */
  let message = null;
  /** Why the work could not be read as the application knows it; gone once it is read. */
  let unread = null;
  let cardsTimer = null;
  let loadedAt = 0;

  /** How many changes are sent and not yet answered, and the values they are about ("facet|key"). */
  let pending = 0;
  const saving = new Set();
  /** Why a change about a value failed, by what it was about: told by the value, not far off in the panel. */
  const failed = new Map();
  /** The changes, sent one after another in the order made. */
  let queue = Promise.resolve();
  /** How many times the page has waited for the chances made after answers: a wait begun before the last change is stale. */
  let settling = 0;

  /**
   * How many of a facet's candidates the user asked to see besides what the page shows, by facet
   * — kept while the page is open — and the candidates read for them, the likeliest first.
   */
  const more = new Map();
  const candidatesOf = new Map();
  /** How many more a press of "…" shows. */
  const MORE_STEP = 5;

  /**
   * THE FIELDS TO ADD A VALUE ARE KEPT THROUGH THE DRAWING OF THE PAGE ANEW, by work and facet
   * ("source/item|facet"): every answer and every reading of the work draws the page again, and a
   * field drawn anew would lose what is typed in it, its focus and its open list. A field the
   * drawing leaves out is dropped.
   */
  const addFields = new Map();
  /** The focus given back to a field after the drawing: not the user's focusing it. */
  let refocusing = false;

  /**
   * What moves the work's prediction ({ of: "source/item", parts }), read apart from the page: of
   * a graded work the application fits a model that had not seen it for the asking, a second or
   * more. After a change it is read anew only once the changes have settled for [SETTLE_MS].
   */
  let explanation = null;
  let explanationTimer = null;
  const SETTLE_MS = 2000;

  // --- Elements ---

  function el(tag, className, text) {
    const e = document.createElement(tag);
    e.setAttribute("data-r4m", "");
    if (className) e.className = className;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  /** A button running [onClick] as a change ([act]); [shown] and [about] as there. */
  /** [element] with the failure of the last change of any of [abouts], when one failed. */
  function withFailures(element, abouts) {
    return withFailure(element, abouts.find((a) => failed.has(a)) || abouts[0]);
  }

  /** [element] with the failure of the last change [about] it after it, when that failed: the element alone else. */
  function withFailure(element, about) {
    const error = failed.get(about);
    if (!error) return element;
    const both = el("r4m-failed");
    both.appendChild(element);
    both.appendChild(Object.assign(el("r4m-failure", null, `⚠ ${t("common.failed", { error })}`), { role: "alert" }));
    return both;
  }

  function button(text, title, onClick, className = "r4m-button", shown = null, about = null) {
    const b = el("button", className, text);
    b.type = "button";
    if (title) b.title = title;
    b.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      act(onClick, shown, about);
    });
    return b;
  }

  /**
   * A button whose change is [about] it alone: busy — marked and not pressed again — while its own
   * press is at work, as the web interface's AsyncButton; never by another button's.
   */
  function ownButton(text, title, onClick, className, shown, about) {
    const b = button(text, title, onClick, className, shown, about);
    if (saving.has(about)) {
      b.classList.add("r4m-saving");
      b.disabled = true;
    }
    return b;
  }

  /**
   * A change run APART FROM THE QUEUE of the answers ([act]): a long one — the catalogue asked for
   * a universe's characters, seconds — does not hold the answers given after it. Marked by its
   * [about] while at work, its failure told there; the work read anew after it, once the answers are quiet.
   */
  function apart(change, about) {
    saving.add(about);
    failed.delete(about);
    render();
    change()
      .catch((e) => failed.set(about, e.message))
      .finally(() => {
        saving.delete(about);
        render();
        if (pending === 0) loadLater();
      });
  }

  /**
   * Runs a change. The value it is [about] is marked as being saved until the application answers
   * — a click must be seen to have landed — and ITS EFFECT IS SHOWN ONCE THE APPLICATION HAS KEPT
   * IT, never before: [shown] changes the work as shown the way the application keeps it. Changes
   * go one after another in the order made, none is dropped, and nothing is read between them: the
   * application only writes them. Once no change has come for [SETTLE_MS] the page is read as the
   * application now knows it ([loadLater]), and again once the chances the answers change are made
   * ([settledLater]).
   */
  function act(change, shown, about) {
    if (pending === 0) message = null;
    pending++;
    // A wait for the chances of the answers before is stale now
    settling++;
    // A new change puts off reading the work and what moves the prediction
    clearTimeout(explanationTimer);
    clearTimeout(loadTimer);
    if (about) {
      saving.add(about);
      failed.delete(about);
    }
    render();
    queue = queue.then(async () => {
      let kept = false;
      try {
        await change();
        kept = true;
      } catch (e) {
        if (about) failed.set(about, e.message);
        else message = e.message;
      }
      if (kept && shown && view && view.item) {
        try {
          shown(view.item);
        } catch (e) {
          // The work read after the quiet shows it all the same
        }
      }
      if (about) saving.delete(about);
      pending--;
      render();
      if (pending === 0) loadLater();
    });
    return queue;
  }

  let loadTimer = null;

  /** The work read as the application now knows it once no change has come for [SETTLE_MS]; the chances the answers change once they are made. */
  function loadLater() {
    clearTimeout(loadTimer);
    loadTimer = setTimeout(async () => {
      if (pending !== 0) return;
      await load();
      explanationLater();
      settledLater();
    }, SETTLE_MS);
  }

  const keyOf = (item) => `${item.summary.source}/${item.summary.item}`;

  /** Reads what moves the prediction of the work once no change has come for [SETTLE_MS]. */
  function explanationLater() {
    clearTimeout(explanationTimer);
    explanationTimer = setTimeout(() => {
      if (view && view.item && pending === 0) readExplanation(view.item);
    }, SETTLE_MS);
  }

  async function readExplanation(item) {
    const of = keyOf(item);
    try {
      const parts = await api("GET", `/api/items/${enc(item.summary.source)}/${enc(item.summary.item)}/explanation`);
      explanation = { of, parts };
    } catch (e) {
      // The panel goes without it
      return;
    }
    if (view && view.item && keyOf(view.item) === of) render();
  }

  /** A score of 0..10 as a class of colour, red to green. */
  function scoreClass(score) {
    return `r4m-s${Math.max(0, Math.min(10, Math.round(score)))}`;
  }

  /** A prediction on 0..10, as the application shows it: above the scale "10+", below it 0. */
  function shownScore(score) {
    return score > 10 ? "10+" : Math.max(0, score).toFixed(1);
  }

  const lower = (text) => (text || "").trim().toLowerCase();

  /** The file an address names: what a preview and its original share. */
  function fileOf(url) {
    try {
      const path = new URL(url, location.href).pathname;
      return decodeURIComponent(path.substring(path.lastIndexOf("/") + 1)).toLowerCase();
    } catch (e) {
      return "";
    }
  }

  // --- Loading ---

  /** The language the server's words kept are of. */
  let wordsLanguage = null;

  async function load() {
    loadedAt = Date.now();
    try {
      // Chosen anew every time (i18n.js given to the page again starts from the browser's): another
      // language chosen meanwhile, the server's words are read in it
      setLanguage(await browser.runtime.sendMessage({ type: "language" }));
      if (wordsLanguage !== r4mLanguage) {
        serverWords.clear();
        wordsLanguage = r4mLanguage;
      }
      server = await browser.runtime.sendMessage({ type: "server" });
      view = await api("GET", `/api/pages?url=${enc(location.href)}`);
      unread = null;
      // The work read anew: the candidates the fields offer may be others now
      addFields.forEach((form) => form.r4mStale());
      // What moves the prediction is read apart, not waited for: a work seen first gets it at once
      if (view.item && (!explanation || explanation.of !== keyOf(view.item))) readExplanation(view.item);
    } catch (e) {
      // What was shown stays, with why it is not current
      unread = e.message;
    }
    render();
    // The candidates asked for are read anew too, after the work is shown: an answer changes which
    // the work may yet have, and they wait for its chances to be made
    if (view && view.item && more.size) {
      try {
        for (const facet of more.keys()) await readCandidates(view.item, facet);
      } catch (e) {
        unread = e.message;
      }
      render();
    }
  }

  /**
   * The work read again once the application has made the chances the answers changed — made
   * apart from them, a second or two. An answer given meanwhile makes this one stale: the reading
   * after it waits for the chances of all of them.
   */
  async function settledLater() {
    const asked = ++settling;
    const item = view && view.item;
    if (!item) return;
    try {
      await api("GET", `/api/items/${enc(item.summary.source)}/${enc(item.summary.item)}/suggestions/settled`);
    } catch (e) {
      return;
    }
    if (asked === settling && pending === 0) await load();
  }

  function clear() {
    document.querySelectorAll("[data-r4m]:not(r4m-badge)").forEach((e) => {
      // A field's own parts stay in it: the field is put back whole ([addFields]). A card's badge
      // stays with what it shows: filled once, it is not asked for again ([cards])
      if (e.parentElement && e.parentElement.closest("form.r4m-add, r4m-badge")) return;
      e.remove();
    });
    document.querySelectorAll(".r4m-removed, .r4m-doubted, .r4m-marked").forEach((e) =>
      e.classList.remove("r4m-removed", "r4m-doubted", "r4m-marked"),
    );
  }

  /**
   * Whether the site's page is dark: by its own background, the body's or the root's — not the
   * system's, which a site with no dark skin (AO3) does not follow.
   */
  function pageIsDark() {
    for (const e of [document.body, document.documentElement]) {
      if (!e) continue;
      const m = getComputedStyle(e).backgroundColor.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?/);
      // Transparent: the colour is of the one under it
      if (!m || (m[4] !== undefined && Number(m[4]) === 0)) continue;
      return 0.2126 * Number(m[1]) + 0.7152 * Number(m[2]) + 0.0722 * Number(m[3]) < 128;
    }
    return false;
  }

  /**
   * Draws the page anew; the field typed in keeps its text, its list and its focus — given back,
   * with the caret where it was.
   */
  function render() {
    const active = document.activeElement;
    const focused = active && active.closest && active.closest("form.r4m-add") ? active : null;
    const caret = focused ? [focused.selectionStart, focused.selectionEnd] : null;
    draw();
    addFields.forEach((form, id) => {
      if (form.isConnected) return;
      form.r4mClose();
      addFields.delete(id);
    });
    if (focused && focused.isConnected && document.activeElement !== focused) {
      refocusing = true;
      try {
        focused.focus({ preventScroll: true });
        if (caret[0] !== null) focused.setSelectionRange(caret[0], caret[1]);
      } finally {
        refocusing = false;
      }
    }
  }

  function draw() {
    clear();
    // The site's skin may change while the page is open
    document.documentElement.setAttribute("data-r4m-theme", pageIsDark() ? "dark" : "light");
    if (!view) return;
    pendingSince = Date.now();
    cards();
    const item = view.item;
    if (!item && !view.itemId) return;
    const placed = new Set();
    try {
      if (item) {
        (view.decor.facets || []).forEach((decor) => {
          if (facetInPlace(item, decor)) placed.add(decor.facet);
        });
        reviews(item);
        pictures(item);
      }
    } catch (e) {
      // A part the page could not take: the panel still shows the work, and why
      console.error("recommend4me", e);
      message = message || String(e.message || e);
    }
    panel(item, placed);
  }

  // --- What a change looks like before the application answers ---

  /** The value [key] of the [facet] as shown, put there if it is not yet; [nameKey] — of a value of the application's own. */
  function shownValue(item, facet, key, name, nameKey) {
    item.facets = item.facets || [];
    let f = item.facets.find((x) => x.facet === facet);
    if (!f) {
      const suggested = (item.suggestions || []).find((x) => x.facet === facet);
      f = { facet, labelKey: suggested && suggested.labelKey, values: [] };
      item.facets.push(f);
    }
    f.values = f.values || [];
    let v = f.values.find((x) => x.key === key);
    if (!v) {
      v = { key, name: name || key, nameKey, offered: true };
      f.values.push(v);
    }
    return v;
  }

  /**
   * The user's word on a value as shown: has it — the site's value confirmed, another added; has
   * it not — taken away. A suggestion answered on is no longer a suggestion.
   */
  function answeredShown(facet, value, added) {
    return (item) => {
      const s = (item.suggestions || []).find((x) => x.facet === facet);
      if (s) s.suggested = (s.suggested || []).filter((x) => x.key !== value.key);
      const v = shownValue(item, facet, value.key, value.name, value.nameKey);
      const site = !v.offered && !v.inferred && v.corrected !== "ADDED";
      v.corrected = added ? (site ? "CONFIRMED" : "ADDED") : "REMOVED";
    };
  }

  /** The user's word on a value taken back, as shown: a value only the user gave goes. */
  function unansweredShown(facet, key) {
    return (item) => {
      const f = (item.facets || []).find((x) => x.facet === facet);
      const v = f && (f.values || []).find((x) => x.key === key);
      if (!v) return;
      if (v.corrected === "ADDED" && !v.inferred) f.values = f.values.filter((x) => x !== v);
      else v.corrected = null;
    };
  }

  const about = (facet, key) => `${facet}|${key}`;

  // --- The panel ---

  function panel(item, placed) {
    const root = el("r4m-panel");
    const head = el("r4m-head");
    head.appendChild(el("strong", null, "recommend4me"));
    if (item && item.summary.prediction) {
      head.appendChild(el("r4m-score", scoreClass(item.summary.prediction.score), shownScore(item.summary.prediction.score)));
    }
    if (item && item.summary.grade) head.appendChild(el("r4m-grade-mark", null, `★${item.summary.grade}`));
    head.appendChild(el("span", "r4m-spacer"));
    if (pending) head.appendChild(el("r4m-saving-note", null, pending > 1 ? t("panel.savingCount", { count: pending }) : t("panel.saving")));
    if (item) {
      const open = el("a", "r4m-link", t("panel.open"));
      open.href = `${server}/#item=${enc(item.summary.source)}/${item.summary.item}`;
      open.target = "_blank";
      open.rel = "noopener";
      head.appendChild(open);
    }
    root.appendChild(head);
    [unread, message].filter(Boolean).forEach((m) => root.appendChild(el("r4m-error", null, m)));
    if (!item) {
      root.appendChild(el("r4m-line", "r4m-muted", t("panel.unsaved")));
      place(root);
      return;
    }
    const s = item.summary;

    const score = el("r4m-line");
    if (s.prediction) {
      score.appendChild(el("span", null, `${t("panel.prediction")} `));
      score.appendChild(el("r4m-score", scoreClass(s.prediction.score), shownScore(s.prediction.score)));
      score.appendChild(el("span", "r4m-muted", ` ${t("panel.outOf")}`));
    } else {
      score.appendChild(el("span", "r4m-muted", t("panel.noPrediction")));
    }
    root.appendChild(score);

    root.appendChild(grades(item));
    root.appendChild(interest(item));

    const parts = explanation && explanation.of === keyOf(item) ? explanation.parts : null;
    if (parts && parts.length) {
      const why = el("r4m-why");
      why.appendChild(el("span", "r4m-muted", `${t("panel.why")} `));
      parts.slice(0, 6).forEach((c, i) => {
        if (i) why.appendChild(el("span", "r4m-muted", " · "));
        const sign = c.contribution >= 0 ? "+" : "−";
        why.appendChild(el("span", c.contribution >= 0 ? "r4m-up" : "r4m-down", `${sign}${Math.abs(c.contribution).toFixed(1)} ${said(c.labelKey)}`));
      });
      root.appendChild(why);
    }

    // The facets the page does not show, in the panel
    facetsOf(item).forEach(({ facet, suggestions }) => {
      if (!placed.has(facet.facet)) root.appendChild(facetBlock(item, facet, suggestions, true));
    });
    place(root);
  }

  /** Every facet of the item with its suggestions, those only suggested included. */
  function facetsOf(item) {
    const out = (item.facets || []).map((facet) => ({ facet, suggestions: (item.suggestions || []).find((x) => x.facet === facet.facet) }));
    (item.suggestions || []).forEach((x) => {
      if (!out.some((f) => f.facet.facet === x.facet)) out.push({ facet: { facet: x.facet, labelKey: x.labelKey, values: [] }, suggestions: x });
    });
    return out;
  }

  /**
   * Puts the panel after the element the source names. A page without such a place — the reader
   * of a chapter — gets no panel: a box floating over the text distracts from reading, and the
   * page is sent all the same.
   */
  function place(root) {
    const anchor = view.decor.panelAfter && document.querySelector(view.decor.panelAfter);
    if (anchor) anchor.insertAdjacentElement("afterend", root);
  }

  /** The grades 1..5 of a work of the page's content type. */
  const GRADES = [1, 2, 3, 4, 5];

  /** The label of a [grade] of the page's content type, by the key of the standard words. */
  const gradeLabel = (grade) => said(`standard:type.${view.type}.grade.${grade}`);

  function grades(item) {
    const s = item.summary;
    const line = el("r4m-line", "r4m-grades");
    line.appendChild(el("span", null, `${t("grade.title")} `));
    GRADES.forEach((grade) => {
      const label = gradeLabel(grade);
      const current = s.grade === grade;
      const b = ownButton(
        `${grade}`,
        current ? t("common.pressAgain", { what: label }) : label,
        () => current
          ? api("DELETE", `/api/items/${enc(s.source)}/${enc(s.item)}/rating`)
          : api("PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/rating`, { grade }),
        `r4m-grade${current ? " r4m-current" : ""}`,
        (shown) => {
          shown.summary.grade = current ? null : grade;
        },
        `grade|${grade}`,
      );
      line.appendChild(b);
    });
    if (s.grade) line.appendChild(el("span", "r4m-muted", ` ${gradeLabel(s.grade)}`));
    else if (s.previousGrade) {
      line.appendChild(el("span", "r4m-muted", ` ${t("grade.previous", { version: s.previousGradeVersion, grade: s.previousGrade })}`));
    }
    return withFailures(line, GRADES.map((grade) => `grade|${grade}`));
  }

  /**
   * Whether the work is interesting to read at all, beside how good it is: the user's mark; without
   * one, the chance the model gives it. A grade says nothing of it.
   */
  function interest(item) {
    const s = item.summary;
    const line = el("r4m-line", "r4m-interest");
    line.appendChild(el("span", null, `${t("interest.title")} `));
    // Not thumbs: a thumb says liked or not, and interest is another thing
    [[true, `🤔 ${t("interest.yes")}`], [false, `🥱 ${t("interest.no")}`]].forEach(([value, label]) => {
      const current = s.interest === value;
      line.appendChild(ownButton(
        label,
        current ? t("common.pressAgainAlone") : null,
        () => current
          ? api("DELETE", `/api/items/${enc(s.source)}/${enc(s.item)}/interest`)
          : api("PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/interest`, { interesting: value }),
        `r4m-mark${current ? " r4m-current" : ""}`,
        (shown) => {
          shown.summary.interest = current ? null : value;
        },
        `interest|${value}`,
      ));
    });
    if (s.interest == null && s.interestChance != null) {
      line.appendChild(el("span", "r4m-muted", ` ${t("interest.byModel", { percent: Math.round(s.interestChance * 100) })}`));
    }
    return withFailures(line, ["interest|true", "interest|false"]);
  }

  // --- Facets ---

  function correct(item, facet, value, added) {
    const s = item.summary;
    return api("PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/corrections/facets`, { facet, ...value, added });
  }

  function uncorrect(item, facet, key) {
    const s = item.summary;
    return api("DELETE", `/api/items/${enc(s.source)}/${enc(s.item)}/corrections/facets?facet=${enc(facet)}&key=${enc(key)}`);
  }

  function percent(chance) {
    return `${Math.round(chance * 100)}%`;
  }

  /**
   * What goes beside a value the user has not answered on: the model's chance in small print —
   * amber when the work more likely has it not — and the user's ✓ (it has it) and ✕ (it has it
   * not). A value the user confirmed or added keeps one button that takes the word back, shown
   * when the chip is pointed at; one the user took away has ↺.
   */
  function answers(item, facet, v) {
    const out = [];
    const on = about(facet, v.key);
    if (v.corrected === "REMOVED") {
      out.push(button("↺", t("value.restore"), () => uncorrect(item, facet, v.key), "r4m-yes", unansweredShown(facet, v.key), on));
      return out;
    }
    if (v.corrected === "ADDED" || v.corrected === "CONFIRMED") {
      out.push(button("✕", t(v.corrected === "ADDED" ? "value.removeAdded" : "value.unanswer"), () => uncorrect(item, facet, v.key), "r4m-undo", unansweredShown(facet, v.key), on));
      return out;
    }
    if (v.implied) {
      // The work has it by a tag under it: no answer of its own, it is the work's while that one is
      out.push(Object.assign(el("r4m-chance", null, "↑"), { title: t("value.implied") }));
      return out;
    }
    if (v.draftedFrom) {
      // Drafted from the site's value through the dictionary: said so, not a chance of the model
      out.push(Object.assign(el("r4m-chance", null, t("value.drafted")), { title: t("value.draftedFrom", { from: v.draftedFrom }) }));
    } else if (v.chance !== undefined && v.chance !== null) {
      const unlikely = v.chance < 0.5;
      out.push(Object.assign(el("r4m-chance", unlikely ? "r4m-unlikely" : null, percent(v.chance)), {
        title: t(unlikely ? "value.unlikely" : "value.confidence"),
      }));
    }
    out.push(button("✓", t("value.yes"), () => correct(item, facet, { key: v.key }, true), "r4m-yes", answeredShown(facet, v, true), on));
    out.push(button("✕", t("value.no"), () => correct(item, facet, { key: v.key }, false), "r4m-no", answeredShown(facet, v, false), on));
    return out;
  }

  /** The order a facet's values of an item were first shown in, by their keys: kept while the page is open. */
  const orders = new Map();

  /**
   * The values in the order first shown — the application's: by the model's chance, the likeliest
   * first, the user's answers aside, a rejected value too, and a tag above another before it: an
   * answer changes how a value looks, never where it is, until the page is opened again. A value
   * that comes later goes after them.
   */
  function stableOrder(item, facet, values) {
    const id = `${item.summary.source}/${item.summary.item}/${facet}`;
    const order = orders.get(id) || [];
    const known = new Set(order);
    const fresh = values.filter((v) => !known.has(v.key)).map((v) => v.key);
    const all = order.concat(fresh);
    orders.set(id, all);
    const at = new Map(all.map((k, i) => [k, i]));
    return [...values].sort((a, b) => at.get(a.key) - at.get(b.key));
  }

  /**
   * A value as a chip, its state told by its look and a sign, not by colour alone: the user's —
   * filled, a ✓ before the name; not answered on — outlined on white, with [answers]; taken away
   * — faded and crossed out. A universe the work is linked to has ↻ to ask the catalogue for its
   * characters again.
   */
  function valueChip(item, facet, v, editable = true) {
    const mine = v.corrected === "ADDED" || v.corrected === "CONFIRMED";
    const chip = el("r4m-chip", mine ? "r4m-mine" : v.corrected === "REMOVED" ? "r4m-removed-chip" : editable ? (v.offered ? "r4m-open r4m-offered" : "r4m-open") : null);
    if (saving.has(about(facet, v.key))) chip.classList.add("r4m-saving");
    if (mine) chip.appendChild(Object.assign(el("r4m-check", null, "✓"), { title: t("value.mine") }));
    chip.appendChild(el("span", null, nameOf(v)));
    // What the catalogue says of a universe or a character: the name alone may be of several
    if (v.description) chip.title = v.description;
    if (editable) answers(item, facet, v).forEach((e) => chip.appendChild(e));
    const at = v.key.indexOf(":");
    let refreshed = null;
    if (facet === UNIVERSE && v.corrected !== "REMOVED" && at > 0) {
      const on = `refresh|${v.key}`;
      refreshed = on;
      const b = el("button", `r4m-button${saving.has(on) ? " r4m-saving" : ""}`, "↻");
      b.type = "button";
      b.title = t("universe.refresh");
      b.disabled = saving.has(on);
      b.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        apart(() => api("POST", "/api/universes", { catalogue: v.key.substring(0, at), universe: v.key.substring(at + 1) }), on);
      });
      chip.appendChild(b);
    }
    return withFailure(chip, refreshed && failed.has(refreshed) ? refreshed : about(facet, v.key));
  }

  /** A suggested value: a dashed chip with its chance, ✓ to add it and ✕ to say the work has it not. */
  function suggestedChip(item, facet, v) {
    const chip = el("r4m-chip", "r4m-suggested");
    chip.title = [v.description, t("value.suggested")].filter(Boolean).join("\n");
    chip.appendChild(el("span", null, nameOf(v)));
    chip.appendChild(el("r4m-chance", null, percent(v.chance)));
    const on = about(facet, v.key);
    // Answered, it stops being a suggestion: shown as the work's value the user added or rejected
    const offered = { key: v.key, name: v.name, nameKey: v.nameKey, offered: true };
    chip.appendChild(button("✓", t("value.yes"), () => correct(item, facet, { key: v.key }, true), "r4m-yes", answeredShown(facet, offered, true), on));
    chip.appendChild(button("✕", t("value.no"), () => correct(item, facet, { key: v.key }, false), "r4m-no", answeredShown(facet, offered, false), on));
    return withFailure(chip, on);
  }

  async function readCandidates(item, facet) {
    const s = item.summary;
    try {
      candidatesOf.set(facet, await api("GET", `/api/items/${enc(s.source)}/${enc(s.item)}/candidates?facet=${enc(facet)}`));
    } catch (e) {
      // A facet the model does not work on has none to show
      candidatesOf.set(facet, []);
    }
  }

  /**
   * The candidates the user asked to see more of — those the line does not show yet ([shown]
   * keys), as many as asked, as suggestions — and "…" to see [MORE_STEP] more, while there are.
   */
  function moreOf(item, facet, line, shown) {
    const asked = more.get(facet) || 0;
    const all = candidatesOf.get(facet);
    const rest = (all || []).filter((c) => !shown.has(c.key));
    rest.slice(0, asked).forEach((c) => {
      const chip = suggestedChip(item, facet, c);
      const named = mentionsText(c.mentions);
      chip.title = [c.description, named ? t("candidate.title", { mentions: named }) : t("value.suggested")].filter(Boolean).join("\n");
      line.appendChild(chip);
    });
    if (all && rest.length <= asked) return;
    const b = el("button", "r4m-button r4m-more", "…");
    b.type = "button";
    // One key, not a word of every plural form: the count is always MORE_STEP, the words written for it
    b.title = t("candidate.more", { count: MORE_STEP });
    b.addEventListener("click", async (e) => {
      e.preventDefault();
      e.stopPropagation();
      more.set(facet, (more.get(facet) || 0) + MORE_STEP);
      if (!candidatesOf.has(facet)) {
        b.classList.add("r4m-saving");
        await readCandidates(item, facet);
      }
      render();
    });
    line.appendChild(b);
  }

  /** The facets the user may say are all named, with the keys of how the button and its hints name them. */
  const ALL_NAMED = {
    [UNIVERSE]: { others: "allNamed.universeOthers", what: "allNamed.universeWhat" },
    characters: { others: "allNamed.charactersOthers", what: "allNamed.charactersWhat" },
    "secondary-characters": { others: "allNamed.secondaryOthers", what: "allNamed.secondaryWhat" },
    pairings: { others: "allNamed.pairingsOthers", what: "allNamed.pairingsWhat" },
  };

  /**
   * "No other universes", "no other pairings": the user's word that the work's values of the
   * facet are all named — the others are not offered and count as rejected for the model; pressed
   * again, taken back. Shown with no value too, as in the application: "none" is an answer as well.
   */
  function completeButton(item, facet) {
    const keys = ALL_NAMED[facet.facet];
    if (!keys || facet.editable === false) return null;
    const names = { others: t(keys.others), what: t(keys.what) };
    const s = item.summary;
    const complete = facet.complete === true;
    const about = `complete|${facet.facet}`;
    return withFailure(ownButton(
      complete ? `✓ ${names.others}` : names.others,
      t(complete ? "allNamed.doneTitle" : "allNamed.title", { what: names.what }),
      () => api(complete ? "DELETE" : "PUT", `/api/items/${enc(s.source)}/${enc(s.item)}/corrections/complete?facet=${enc(facet.facet)}`),
      `r4m-button r4m-complete${complete ? " r4m-current" : ""}`,
      (shown) => {
        const f = (shown.facets || []).find((x) => x.facet === facet.facet);
        if (!f) return;
        f.complete = !complete;
        // Said complete: nothing more is offered
        if (!complete) {
          f.values = (f.values || []).filter((v) => !v.offered);
          const s2 = (shown.suggestions || []).find((x) => x.facet === facet.facet);
          if (s2) s2.suggested = [];
        }
      },
      about,
    ), about);
  }

  /** The keys of the values a facet's line shows: the work's, the user's, the suggested. */
  function shownKeys(facet, suggestions) {
    return new Set([...(facet.values || []), ...((suggestions && suggestions.suggested) || [])].map((v) => v.key));
  }

  /** "mentioned 3 times" — how often the work's texts name a candidate; empty when they were not counted. */
  function mentionsText(n) {
    if (n === undefined || n === null) return "";
    if (n === 0) return t("candidate.notMentioned");
    return t("candidate.mentions", { count: n });
  }

  /** A name as it is matched: any case, "ё" as "е". */
  const folded = (text) => (text || "").toLowerCase().replace(/ё/g, "е");

  /**
   * A field to add a value by its name, with a list of hints of its own under it: of a facet that
   * says which values the work may have, every candidate with the model's chance and how often the
   * work's texts name it, the likeliest first, narrowed to those whose name has every word typed,
   * anywhere in it; of any other facet, the facet's values as they are typed, how many works have
   * each. A hint chosen is added by its key, a name typed and sent by its name.
   *
   * NOT A <datalist>: a universe's candidates are thousands, and Safari on an iPad draws them all
   * at once, slowly, by the value alone — without the chance and the mentions — matching the start
   * of a name only. The list here draws what is scrolled to, a few screens at a time.
   */
  function addField(item, facet, label) {
    const id = `${keyOf(item)}|${facet}`;
    const kept = addFields.get(id);
    if (kept && !kept.isConnected) return kept;
    const form = el("form", "r4m-add");
    const input = el("input");
    input.type = "text";
    input.placeholder = `+ ${lower(label)}`;
    input.autocomplete = "off";
    input.setAttribute("role", "combobox");
    input.setAttribute("aria-autocomplete", "list");
    input.setAttribute("aria-expanded", "false");
    const box = el("r4m-options");
    box.setAttribute("role", "listbox");
    box.hidden = true;
    const s = item.summary;
    const chosen = CHOSEN.includes(facet);

    /**
     * Every hint known: { name — as shown, key, nameKey?, description?, note }; and those the typing leaves, drawn
     * up to [drawn], the one gone to by the keys [active].
     */
    let hints = [];
    let shown = [];
    let drawn = 0;
    let active = -1;

    /** Adds a value: a hint by its key, a name typed ([key] none) by its name. */
    function add(name, key, nameKey) {
      if (!name) return;
      form.r4mStale();
      act(() => correct(item, facet, key ? { key } : { name }, true), answeredShown(facet, { key: key || name, name, nameKey, offered: true }, true), about(facet, key || name));
    }

    function close() {
      box.hidden = true;
      active = -1;
      input.setAttribute("aria-expanded", "false");
      document.removeEventListener("pointerdown", outside, true);
    }

    /** A press anywhere but the field and its list closes the list; not the field's blur — on an iPad it comes before the tap on a row does. */
    function outside(e) {
      if (!form.contains(e.target)) close();
    }

    /** A row of a hint: its name, and under it what tells it apart; pressed, it is added. */
    function row(h, i) {
      const r = el("r4m-option", i === active ? "r4m-active" : null);
      r.setAttribute("role", "option");
      r.appendChild(el("r4m-option-name", null, h.name));
      // What the catalogue says of it: two of one name told apart
      if (h.description) r.appendChild(el("r4m-option-note", null, h.description));
      if (h.note) r.appendChild(el("r4m-option-note", null, h.note));
      // A mouse's press keeps the focus in the field; a finger's is the start of a scroll as often as of a tap
      r.addEventListener("pointerdown", (e) => {
        if (e.pointerType === "mouse") e.preventDefault();
      });
      // A tap, not a scroll: the click comes only of a touch that did not move
      r.addEventListener("click", () => {
        close();
        input.value = "";
        add(h.name, h.key, h.nameKey);
      });
      return r;
    }

    /**
     * The rows drawn on until a screen of the list beyond what is seen: a flick does not reach the
     * end before more are drawn. A list not yet as high as it may be grows a row at a time.
     */
    function drawMore() {
      while (drawn < shown.length && box.scrollHeight <= box.scrollTop + 2 * box.clientHeight) {
        box.appendChild(row(shown[drawn], drawn));
        drawn++;
      }
    }

    function show() {
      box.textContent = "";
      box.scrollTop = 0;
      drawn = 0;
      active = -1;
      if (!shown.length || document.activeElement !== input) return close();
      box.hidden = false;
      input.setAttribute("aria-expanded", "true");
      document.addEventListener("pointerdown", outside, true);
      drawMore();
    }

    /** The hints whose name has every word typed, in their order. */
    function narrow() {
      const words = folded(input.value).split(/\s+/).filter(Boolean);
      shown = chosen ? hints.filter((h) => words.every((w) => folded(h.name).includes(w))) : hints;
      show();
    }

    function go(step) {
      if (box.hidden || !shown.length) return;
      active = Math.max(0, Math.min(shown.length - 1, active + step));
      while (drawn <= active) {
        box.appendChild(row(shown[drawn], drawn));
        drawn++;
      }
      [...box.children].forEach((r, i) => r.classList.toggle("r4m-active", i === active));
      box.children[active].scrollIntoView({ block: "nearest" });
    }

    box.addEventListener("scroll", drawMore);
    form.r4mClose = close;
    form.r4mStale = () => {};
    if (chosen) {
      /** The reading of the candidates, once for the work as last read: read again after it is read anew ([form.r4mStale]). */
      let reading = null;
      const hintsRead = () => {
        if (!reading) {
          reading = api("GET", `/api/items/${enc(s.source)}/${enc(s.item)}/candidates?facet=${enc(facet)}`).then(
            (candidates) => {
              hints = candidates.map((c) => ({
                name: nameOf(c),
                key: c.key,
                nameKey: c.nameKey,
                description: c.description,
                note: [c.chance !== undefined && c.chance !== null ? percent(c.chance) : "", mentionsText(c.mentions)].filter(Boolean).join(" · "),
              }));
            },
            () => {
              // No hints: the value is added by its name all the same; asked again on the next typing
              reading = null;
            },
          );
        }
        return reading;
      };
      form.r4mStale = () => {
        reading = null;
      };
      input.addEventListener("focus", async () => {
        // The focus given back after the drawing: the list stays as it was
        if (refocusing) return;
        await hintsRead();
        narrow();
      });
      input.addEventListener("input", async () => {
        await hintsRead();
        narrow();
      });
    } else {
      let timer = null;
      input.addEventListener("input", () => {
        clearTimeout(timer);
        timer = setTimeout(async () => {
          try {
            const values = await api("GET", `/api/sources/${enc(s.source)}/facets/${enc(facet)}/values?query=${enc(input.value)}&limit=20`);
            // By its key: a value of the application's own is shown by words the name typed would not match
            hints = values.map((v) => ({ name: nameOf(v), key: v.key, nameKey: v.nameKey, note: t("facet.works", { count: v.items }) }));
            narrow();
          } catch (e) {
            // No hints: the value is added by its name all the same
          }
        }, 250);
      });
    }
    input.addEventListener("keydown", (e) => {
      // The site's own keys must not see the typing
      e.stopPropagation();
      if (e.key === "ArrowDown") {
        e.preventDefault();
        if (box.hidden) narrow();
        else go(1);
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        go(-1);
      } else if (e.key === "Escape" || e.key === "Tab") {
        close();
      }
    });
    ["keyup", "keypress"].forEach((type) => input.addEventListener(type, (e) => e.stopPropagation()));
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      // The hint gone to by the keys; else a hint of just the name typed; else the name as it is —
      // of a facet of the dictionary's entries alone, none: the list stays open to choose from
      const typed = input.value.trim();
      const h = active >= 0 ? shown[active] : hints.find((x) => folded(x.name) === folded(typed));
      if (!h && KEYS_ONLY.includes(facet)) {
        input.setAttribute("aria-invalid", "true");
        note.hidden = false;
        narrow();
        return;
      }
      close();
      input.value = "";
      if (h) add(h.name, h.key, h.nameKey);
      else add(typed, null);
    });
    // Said under the field, not in a title: a finger sees no title
    const note = el("r4m-add-note", null, t("facet.chooseHint"));
    note.hidden = true;
    note.setAttribute("role", "alert");
    input.addEventListener("input", () => {
      note.hidden = true;
      input.removeAttribute("aria-invalid");
    });
    form.appendChild(input);
    form.appendChild(box);
    form.appendChild(note);
    if (!kept) addFields.set(id, form);
    return form;
  }

  /**
   * A facet as a block: its label, the site's own line of it when the panel shows it, its values
   * with the user's answers, the suggestions, the field to add one.
   */
  function facetBlock(item, facet, suggestions, inPanel) {
    const block = el("r4m-facet");
    const label = el("r4m-label", null, labelOf(facet));
    block.appendChild(label);
    if (inPanel && facet.original) block.appendChild(el("r4m-original", null, t("facet.onSite", { values: facet.original })));
    const line = el("r4m-chips");
    // A facet of the site is its word: shown, never answered on
    const editable = facet.editable !== false;
    const values = facet.values || [];
    // The work's values the user has not answered on — not those only offered, nor those it has by a tag under them
    const open = values.filter((v) => !v.corrected && !v.offered && !v.implied);
    if (editable && open.length > 1) {
      const all = `all|${facet.facet}`;
      label.appendChild(withFailure(ownButton(t("facet.confirmAll", { count: open.length }), t("facet.confirmAllTitle"), async () => {
        for (const v of open) await correct(item, facet.facet, { key: v.key }, true);
      }, "r4m-button r4m-all", (shown) => open.forEach((v) => answeredShown(facet.facet, v, true)(shown)), all), all));
    }
    const allNamed = completeButton(item, facet);
    if (allNamed) label.appendChild(allNamed);
    stableOrder(item, facet.facet, values).forEach((v) => line.appendChild(valueChip(item, facet.facet, v, editable)));
    if (editable) {
      ((suggestions && suggestions.suggested) || []).forEach((v) => line.appendChild(suggestedChip(item, facet.facet, v)));
      if (!facet.complete) moreOf(item, facet.facet, line, shownKeys(facet, suggestions));
      line.appendChild(addField(item, facet.facet, labelOf(facet)));
    }
    block.appendChild(line);
    return block;
  }

  /**
   * The facet on the page: on the site's own elements of its values ([decor.values]) — the
   * answers beside each, the user's values and the suggestions after the last — or as a block of
   * its own after the element [decor.after]. False when the page has neither.
   */
  function facetInPlace(item, decor) {
    const suggestions = (item.suggestions || []).find((x) => x.facet === decor.facet);
    const facet = (item.facets || []).find((f) => f.facet === decor.facet) || { facet: decor.facet, labelKey: suggestions && suggestions.labelKey, values: [] };
    if (decor.after) {
      const anchor = document.querySelector(decor.after);
      if (!anchor) return false;
      // After the blocks of the facets put there before it, in the order the source lists them
      let at = anchor;
      while (at.nextElementSibling && at.nextElementSibling.tagName === "R4M-FACET") at = at.nextElementSibling;
      at.insertAdjacentElement("afterend", facetBlock(item, facet, suggestions, false));
      return true;
    }
    const elements = [...document.querySelectorAll(decor.values)].filter((e) => !e.closest("[data-r4m]"));
    if (!elements.length) return false;
    const values = facet.values || [];
    elements.forEach((e) => {
      const label = lower(e.getAttribute("title") || e.textContent);
      // The site writes a value as the data has it; a value of the application's own, maybe as the words say it
      const v = values.find((x) => lower(x.name) === label || lower(x.key) === label || lower(nameOf(x)) === label);
      if (!v || v.corrected === "ADDED") return;
      if (v.corrected === "REMOVED") e.classList.add("r4m-removed");
      else if (v.corrected !== "CONFIRMED" && v.chance !== undefined && v.chance !== null && v.chance < 0.5) e.classList.add("r4m-doubted");
      const tools = el("r4m-tools", saving.has(about(decor.facet, v.key)) ? "r4m-saving" : null);
      answers(item, decor.facet, v).forEach((a) => tools.appendChild(a));
      const error = failed.get(about(decor.facet, v.key));
      if (error) tools.appendChild(Object.assign(el("r4m-failure", null, `⚠ ${t("common.failed", { error })}`), { role: "alert" }));
      e.insertAdjacentElement("afterend", tools);
    });
    const after = el("r4m-chips");
    values.filter((v) => v.corrected === "ADDED" || (v.inferred && v.corrected !== "REMOVED")).forEach((v) => after.appendChild(valueChip(item, decor.facet, v)));
    ((suggestions && suggestions.suggested) || []).forEach((v) => after.appendChild(suggestedChip(item, decor.facet, v)));
    if (!facet.complete) moreOf(item, decor.facet, after, shownKeys(facet, suggestions));
    after.appendChild(addField(item, decor.facet, labelOf(facet)));
    const allNamed = completeButton(item, facet);
    if (allNamed) after.appendChild(allNamed);
    const last = elements[elements.length - 1];
    (last.nextElementSibling && last.nextElementSibling.tagName === "R4M-TOOLS" ? last.nextElementSibling : last)
      .insertAdjacentElement("afterend", after);
    return true;
  }

  // --- Reviews and pictures ---

  /** "+ / −" of a review or a picture; [set] shows the new mark (or none) before the application answers. */
  function marks(current, put, remove, what, set, on) {
    const bar = el("r4m-marks", saving.has(on) ? "r4m-saving" : null);
    const to = (mark) => (current === mark ? null : mark);
    bar.appendChild(button("+", t("mark.plus", { what }), () => (current === 1 ? remove() : put(1)), `r4m-mark${current === 1 ? " r4m-current" : ""}`, (shown) => set(shown, to(1)), on));
    bar.appendChild(button("−", t("mark.minus", { what }), () => (current === -1 ? remove() : put(-1)), `r4m-mark${current === -1 ? " r4m-current" : ""}`, (shown) => set(shown, to(-1)), on));
    return bar;
  }

  function reviews(item) {
    const decor = view.decor.reviews;
    if (!decor) return;
    const s = item.summary;
    const base = `/api/items/${enc(s.source)}/${enc(s.item)}/reviews`;
    document.querySelectorAll(decor.selector).forEach((e) => {
      const id = (e.getAttribute(decor.idAttribute) || "").replace(decor.idPrefix, "");
      if (!id) return;
      const current = item.reviewMarks[id];
      const bar = marks(
        current,
        (mark) => api("PUT", `${base}/${enc(id)}/mark`, { mark }),
        () => api("DELETE", `${base}/${enc(id)}/mark`),
        t("mark.review"),
        (shown, mark) => {
          if (mark) shown.reviewMarks[id] = mark;
          else delete shown.reviewMarks[id];
        },
        `review|${id}`,
      );
      if (current) e.classList.add("r4m-marked");
      e.appendChild(bar);
    });
  }

  function pictures(item) {
    const selector = view.decor.pictures;
    if (!selector || !item.pictures.length) return;
    const s = item.summary;
    const byFile = new Map(item.pictures.map((p) => [fileOf(p.url), p]));
    const done = new Set();
    document.querySelectorAll(selector).forEach((img) => {
      const link = img.closest("a");
      const candidates = [img.getAttribute("src"), img.getAttribute("data-src"), img.getAttribute("data-url"), link && link.getAttribute("href")];
      const picture = candidates.map((u) => u && byFile.get(fileOf(u))).find((p) => p);
      if (!picture || done.has(picture.position)) return;
      done.add(picture.position);
      const base = `/api/items/${enc(s.source)}/${enc(s.item)}/pictures/${picture.position}/mark`;
      const bar = marks(
        picture.mark, (mark) => api("PUT", base, { mark }), () => api("DELETE", base), t(picture.position === 0 ? "mark.cover" : "mark.picture"),
        (shown, mark) => {
          const p = shown.pictures.find((x) => x.position === picture.position);
          if (p) p.mark = mark;
        },
        `picture|${picture.position}`,
      );
      bar.classList.add("r4m-picture-marks");
      (link || img).insertAdjacentElement("afterend", bar);
    });
  }

  // --- Cards of a list ---

  /**
   * How often a list asks again for the works whose prediction is still worked out — a work first
   * seen is read by the text model and scored seconds after it is saved — and for how long: the
   * patience of a page, not meaning.
   */
  const PENDING_EVERY = 4 * 1000;
  const PENDING_FOR = 2 * 60 * 1000;
  let pendingSince = 0;
  let pendingTimer = null;

  /**
   * The prediction and the grade on every card of a work the application knows, all the cards in
   * one call. A badge is filled in place, never taken off: a work whose prediction is still worked
   * out shows "…" and is asked for again every few seconds, the others are not asked again.
   */
  async function cards() {
    const decor = view && view.decor.cards;
    if (!decor) return;
    const wanted = [];
    document.querySelectorAll(decor.selector).forEach((card) => {
      if (card.closest("[data-r4m]")) return;
      const badge = card.querySelector("r4m-badge");
      if (badge && !badge.classList.contains("r4m-pending")) return;
      const link = card.querySelector(decor.link);
      if (link && link.getAttribute("href")) wanted.push({ card, link });
    });
    if (!wanted.length) return;
    let known = [];
    try {
      known = await api("POST", "/api/pages/cards", { url: location.href, links: wanted.map((w) => w.link.getAttribute("href")) });
    } catch (e) {
      return;
    }
    const byLink = new Map(known.map((k) => [k.link, k]));
    let pending = false;
    wanted.forEach(({ card, link }) => {
      let badge = card.querySelector("r4m-badge");
      if (!badge) {
        badge = el("r4m-badge");
        link.insertAdjacentElement("beforebegin", badge);
      }
      // A work the application does not know yet waits, marked, until the page that shows it is saved
      const k = byLink.get(link.getAttribute("href"));
      const waiting = k ? fillBadge(badge, k) : unsaved(badge);
      pending = pending || waiting;
    });
    clearTimeout(pendingTimer);
    if (pending && Date.now() - pendingSince < PENDING_FOR) pendingTimer = setTimeout(cards, PENDING_EVERY);
  }

  /** The badge of a card of a work the application does not know yet: "…" until the page is saved. */
  function unsaved(badge) {
    badge.textContent = "";
    const score = el("r4m-score", "r4m-pending-score", "…");
    score.title = t("card.saving");
    badge.appendChild(score);
    badge.classList.add("r4m-pending");
    return true;
  }

  /** The badge of a card filled with what the application knows of the work [k]; true while its prediction is still worked out. */
  function fillBadge(badge, k) {
    badge.textContent = "";
    const scored = k.prediction !== undefined && k.prediction !== null;
    const score = scored ? el("r4m-score", scoreClass(k.prediction), shownScore(k.prediction)) : el("r4m-score", "r4m-pending-score", "…");
    const waiting = !scored && !k.grade;
    score.title = t(waiting ? "card.pending" : "card.prediction");
    badge.appendChild(score);
    if (k.grade) {
      const grade = el("r4m-grade-mark", null, `★${k.grade}`);
      grade.title = t("card.grade");
      badge.appendChild(grade);
    }
    badge.appendChild(cardInterest(badge, k, true, "🤔"));
    badge.appendChild(cardInterest(badge, k, false, "🥱"));
    // A mark that could not be saved: why, in the badge's title
    const error = [true, false].map((value) => failed.get(`card|${k.source}/${k.item}|${value}`)).find(Boolean);
    badge.title = error ? t("card.notSaved", { error }) : "";
    badge.classList.toggle("r4m-pending", waiting);
    return waiting;
  }

  /**
   * A mark of interest on a card of a list — quicker to give than a grade, so given right there. A
   * change as any other ([act]): the button busy while its press is at work, the mark shown once the
   * application has kept it, the error in the badge's title.
   */
  function cardInterest(badge, k, value, icon) {
    const current = k.interest === value;
    const word = t(value ? "interest.yesTitle" : "interest.noTitle");
    const on = `card|${k.source}/${k.item}|${value}`;
    const busy = saving.has(on);
    const b = el("button", `r4m-interest-mark${current ? " r4m-current" : ""}${busy ? " r4m-saving" : ""}`, icon);
    b.type = "button";
    b.disabled = busy;
    const chance = k.interest == null && k.interestChance != null
      ? ` ${t("card.interestChance", { percent: Math.round(k.interestChance * 100) })}`
      : "";
    b.title = current ? t("common.pressAgain", { what: word }) : `${word}${chance}`;
    b.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      const path = `/api/items/${enc(k.source)}/${enc(k.item)}/interest`;
      act(async () => {
        const s = current ? await api("DELETE", path) : await api("PUT", path, { interesting: value });
        Object.assign(k, { interest: s.interest, interestChance: s.interestChance });
      }, null, on).then(() => fillBadge(badge, k));
      fillBadge(badge, k);
    });
    return b;
  }

  // Lists that load more cards as the user scrolls
  new MutationObserver((mutations) => {
    if (!view || !view.decor.cards) return;
    const theirs = mutations.some((m) => [...m.addedNodes].some((n) => n.nodeType === 1 && !n.hasAttribute("data-r4m")));
    if (!theirs) return;
    clearTimeout(cardsTimer);
    cardsTimer = setTimeout(cards, 800);
  }).observe(document.body, { childList: true, subtree: true });

  // A page sent again as it grows. A list: the works it just saved get their badges, the page is
  // not read anew. A work's page: read anew once it is first saved, and then seldom
  browser.runtime.onMessage.addListener((m) => {
    if (m.type !== "captured" || m.url !== location.href) return;
    // Answers on their way: the page is read anew when the last is answered
    if (pending) return;
    if (view && !view.item && !view.itemId) {
      pendingSince = Date.now();
      cards();
      return;
    }
    if (!view || !view.item || Date.now() - loadedAt > RELOAD_AFTER) load();
  });

  window.__recommend4mePage = { load };
  load();
})();
