/* The popup: whether the application answers, the switch, the last pages sent. */

async function render() {
  const status = await browser.runtime.sendMessage({ type: "status" });
  document.getElementById("enabled").checked = status.enabled;
  const line = document.getElementById("status");
  line.className = status.ok ? "ok" : "error";
  line.textContent = status.ok
    ? t("popup.answers", { server: status.server, sources: status.sources.join(", ") || t("popup.noSources") })
    : t("popup.silent", { server: status.server, error: status.error });

  const recent = await browser.runtime.sendMessage({ type: "recent" });
  const list = document.getElementById("recent");
  list.textContent = "";
  if (!recent.length) {
    const li = document.createElement("li");
    li.className = "muted";
    li.textContent = t("popup.nothingSent");
    list.appendChild(li);
  }
  recent.forEach((entry) => {
    const li = document.createElement("li");
    const time = new Date(entry.at).toLocaleTimeString(r4mLocale());
    if (entry.error) {
      li.className = "error";
      li.textContent = t("popup.failed", { time, url: entry.url, error: entry.error });
    } else if (!entry.source) {
      li.className = "muted";
      li.textContent = t("popup.unwanted", { time, url: entry.url });
    } else {
      const parts = entry.parts || [];
      const works = entry.items.map((i) => i.title).join(", ");
      li.textContent = `${time} ${entry.source}: ${works || (parts.length ? "" : t("popup.nothingNew"))}`;
      // Every chapter whose text went: its title and how long it is
      parts.forEach((p) => {
        const line = document.createElement("div");
        line.className = "part";
        const title = p.title ? t("popup.partTitle", { title: p.title }) : t("popup.untitled");
        const of = p.itemTitle && !works.includes(p.itemTitle) ? ` (${p.itemTitle})` : "";
        line.textContent = t("popup.partText", { title, of, length: p.length.toLocaleString(r4mLocale()) });
        li.appendChild(line);
      });
    }
    list.appendChild(li);
  });
}

document.getElementById("enabled").addEventListener("change", async (e) => {
  const settings = await loadSettings();
  await saveSettings({ ...settings, enabled: e.target.checked });
  render();
});

document.getElementById("send").addEventListener("click", async () => {
  const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
  await browser.runtime.sendMessage({ type: "send-now", tabId: tab.id });
  render();
});

document.getElementById("options").addEventListener("click", (e) => {
  e.preventDefault();
  browser.runtime.openOptionsPage();
});

document.getElementById("app").addEventListener("click", async (e) => {
  e.preventDefault();
  const { server } = await loadSettings();
  browser.tabs.create({ url: server });
});

(async () => {
  setLanguage((await loadSettings()).language);
  translatePage();
  render();
})();
