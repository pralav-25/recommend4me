/*
 * THE USERSCRIPT FOR SAFARI ON THE IPAD, run by the Userscripts app: what the background script
 * of the Firefox extension does, done in the page itself. Not a part of the Firefox extension — its
 * manifest does not name it; the application puts it together with i18n.js, watch.js and page.js
 * into recommend4me.user.js (GET /userscript/recommend4me.user.js), with these given:
 *
 *   R4M_SERVER — where the application was asked for the script, the address the iPad reaches it at;
 *   R4M_CSS    — page.css;
 *   runPage()  — page.js.
 *
 * The calls to the application go through GM.xmlHttpRequest: the userscript's own requests, not
 * bound by the page's origin, as the extension's background script is not.
 */

/** The listeners of messages page.js registers: told when the page was sent and saved. */
const listeners = [];

/** A call to the application; answers {ok, status, data, error, problem, unreachable} as the extension's background script does. */
function request(method, path, body) {
  return new Promise((resolve) => {
    const failed = () => resolve({ ok: false, status: 0, error: "no answer", unreachable: true });
    GM.xmlHttpRequest({
      method,
      url: `${R4M_SERVER}${path}`,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      data: body === undefined ? undefined : JSON.stringify(body),
      onload: (response) => {
        let data = null;
        try {
          data = response.responseText ? JSON.parse(response.responseText) : null;
        } catch (e) {
          data = null;
        }
        const ok = response.status >= 200 && response.status < 300;
        resolve(ok ? { ok, status: response.status, data } : {
          ok,
          status: response.status,
          error: (data && data.detail) || `HTTP ${response.status}`,
          problem: data && data.code ? { code: data.code, params: data.params || {} } : null,
        });
      },
      onerror: failed,
      ontimeout: failed,
    });
  });
}

/** Whether page.js may call the application at [path]: its API, and the files of its words (as the background script's callable). */
function callable(method, path) {
  if (typeof path !== "string") return false;
  return path.startsWith("/api/") || ((method || "GET") === "GET" && /^\/i18n\/[\w.-]+\/[\w-]+\.json$/.test(path));
}

/** The stand-in of the extension's API that watch.js and page.js call. */
const browser = {
  runtime: {
    sendMessage: async (message) => {
      if (message.type === "server") return R4M_SERVER;
      // No options page on the iPad: the browser's language
      if (message.type === "language") return "";
      if (message.type === "api") {
        if (!callable(message.method, message.path)) return { ok: false, status: 0, error: "not allowed" };
        return request(message.method || "GET", message.path, message.body);
      }
      if (message.type === "capture") {
        const sent = await request("POST", "/api/capture", { url: message.url, html: message.html });
        // The page shows the work as the application knows it now
        if (sent.ok) listeners.forEach((listener) => listener({ type: "captured", url: message.url }));
        return sent;
      }
      return undefined;
    },
    onMessage: { addListener: (listener) => listeners.push(listener) },
  },
};

/** Whether a source of the application wants the page: the addresses asked for every address the page goes to. */
async function wanted(url) {
  const answer = await request("GET", "/api/capture/patterns");
  if (!answer.ok) return false;
  return answer.data.some((p) => p.patterns.some((re) => new RegExp(`^(?:${re})$`).test(url)));
}

/** page.css, once: marked apart from page.js's own elements, which it clears on every drawing. */
function addStyle() {
  if (document.querySelector("style[data-r4m-style]")) return;
  const style = document.createElement("style");
  style.setAttribute("data-r4m-style", "");
  style.textContent = R4M_CSS;
  (document.head || document.documentElement).appendChild(style);
}

/** A wanted page is sent as it grows, and the work is shown on it. */
async function start() {
  if (!(await wanted(location.href))) return;
  addStyle();
  window.__recommend4meWatching = false;
  watchPage();
  runPage();
}

// A site that changes its address without loading a page (a reader turning to the next chapter):
// the page's own history calls are out of a userscript's reach, the address is looked at instead
let address = location.href;
setInterval(() => {
  if (location.href === address) return;
  address = location.href;
  start();
}, 1000);

start();
