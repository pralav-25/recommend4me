/*
 * THE BROWSER TRACKING MODE: every page the user opens whose address one of the application's
 * sources wants is sent to the application — the DOM as the user sees it, after the scripts of the
 * page have filled it (a reader loads the text of a chapter after the page itself).
 *
 * The application says which addresses it wants (GET /api/capture/patterns); nothing else leaves
 * the browser. A page is sent when it has loaded, and again when its content grows while the user
 * is on it, at most once every few seconds and not after a minute.
 *
 * AND THE SITE AS THE APPLICATION'S INTERFACE: on the same pages page.js shows what the
 * application knows of the work and lets the user grade it, correct its tags, mark its reviews and
 * pictures (see page.js). Its calls to the application go through here: the page's own origin may
 * not call the application, the extension may.
 */

/** The wanted addresses, as regular expressions, and when they were asked for. */
let patterns = [];
let patternsAt = 0;
const PATTERNS_TTL = 10 * 60 * 1000;

/** What happened to the last pages, for the popup: newest first. */
const recent = [];
const RECENT = 20;

/** Whether a source of the application wants the page. */
async function wanted(url) {
  const { server } = await loadSettings();
  if (Date.now() - patternsAt > PATTERNS_TTL) {
    try {
      const response = await fetch(`${server}/api/capture/patterns`);
      if (response.ok) {
        patterns = (await response.json()).flatMap((p) => p.patterns.map((re) => new RegExp(`^(?:${re})$`)));
        patternsAt = Date.now();
      }
    } catch (e) {
      // The application is not running: nothing is wanted until it is
      patterns = [];
      patternsAt = Date.now() - PATTERNS_TTL + 30 * 1000;
    }
  }
  return patterns.some((re) => re.test(url));
}

// watchPage — the script put into a wanted page — is in watch.js, shared with the userscript

async function inject(tabId, url) {
  const { enabled, decorate } = await loadSettings();
  if (!(enabled || decorate) || !(await wanted(url))) return;
  if (enabled) {
    try {
      await browser.scripting.executeScript({ target: { tabId }, func: watchPage });
    } catch (e) {
      console.warn("recommend4me: cannot watch", url, e);
    }
  }
  if (!decorate) return;
  try {
    await browser.scripting.insertCSS({ target: { tabId }, files: ["page.css"] });
    await browser.scripting.executeScript({ target: { tabId }, files: ["page.js"] });
  } catch (e) {
    console.warn("recommend4me: cannot show the work on", url, e);
  }
}

browser.webNavigation.onCompleted.addListener((details) => {
  if (details.frameId === 0) inject(details.tabId, details.url);
});

// A site that changes its address without loading a page (a reader turning to the next chapter)
browser.webNavigation.onHistoryStateUpdated.addListener(async (details) => {
  if (details.frameId !== 0) return;
  try {
    await browser.scripting.executeScript({
      target: { tabId: details.tabId },
      func: () => {
        window.__recommend4meWatching = false;
      },
    });
  } catch (e) {
    // The page is gone
  }
  inject(details.tabId, details.url);
});

async function capture(url, html, tabId) {
  const { server } = await loadSettings();
  const entry = { url, at: Date.now(), items: [], error: null };
  try {
    const response = await fetch(`${server}/api/capture`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url, html }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const result = await response.json();
    entry.items = result.items;
    entry.source = result.source;
    // The chapters whose text the page gave: the user sees it went, not only the work's card
    entry.parts = result.parts || [];
    if (tabId !== undefined) {
      await browser.action.setBadgeBackgroundColor({ color: "#2e7d32", tabId });
      await browser.action.setBadgeText({ text: result.items.length ? String(result.items.length) : "✓", tabId });
      // The page shows the work as the application knows it now
      browser.tabs.sendMessage(tabId, { type: "captured", url }).catch(() => {});
    }
  } catch (e) {
    entry.error = String(e.message || e);
    if (tabId !== undefined) {
      await browser.action.setBadgeBackgroundColor({ color: "#c62828", tabId });
      await browser.action.setBadgeText({ text: "!", tabId });
    }
  }
  recent.unshift(entry);
  recent.length = Math.min(recent.length, RECENT);
  return entry;
}

/**
 * A call of page.js to the application: only to its API, only from the extension's own scripts.
 * Answers {ok, status, data, error}: the body as JSON, or the "detail" of an error.
 */
async function call(message, sender) {
  if (sender.id !== browser.runtime.id || typeof message.path !== "string" || !message.path.startsWith("/api/")) {
    return { ok: false, status: 0, error: "not allowed" };
  }
  const { server } = await loadSettings();
  try {
    const response = await fetch(`${server}${message.path}`, {
      method: message.method || "GET",
      headers: message.body === undefined ? {} : { "Content-Type": "application/json" },
      body: message.body === undefined ? undefined : JSON.stringify(message.body),
    });
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (e) {
      data = null;
    }
    if (!response.ok) return { ok: false, status: response.status, error: (data && data.detail) || `HTTP ${response.status}` };
    return { ok: true, status: response.status, data };
  } catch (e) {
    return { ok: false, status: 0, error: "Приложение recommend4me не отвечает" };
  }
}

browser.runtime.onMessage.addListener((message, sender) => {
  if (message.type === "capture") return capture(message.url, message.html, sender.tab && sender.tab.id);
  if (message.type === "api") return call(message, sender);
  if (message.type === "server") return loadSettings().then((s) => s.server);
  if (message.type === "recent") return Promise.resolve(recent);
  if (message.type === "status") return status();
  if (message.type === "send-now") return sendNow(message.tabId);
  if (message.type === "patterns-changed") {
    patternsAt = 0;
    return Promise.resolve(true);
  }
  return undefined;
});

/** Whether the application answers, and what it wants. */
async function status() {
  const { server, enabled } = await loadSettings();
  try {
    const response = await fetch(`${server}/api/capture/patterns`);
    if (!response.ok) return { server, enabled, ok: false, error: `HTTP ${response.status}` };
    const sources = await response.json();
    return { server, enabled, ok: true, sources: sources.map((s) => s.source) };
  } catch (e) {
    return { server, enabled, ok: false, error: String(e.message || e) };
  }
}

/** The page of a tab sent now, by the popup's button, whatever the patterns say. */
async function sendNow(tabId) {
  const [{ result }] = await browser.scripting.executeScript({
    target: { tabId },
    func: () => ({ url: location.href, html: document.documentElement.outerHTML }),
  });
  return capture(result.url, result.html, tabId);
}
