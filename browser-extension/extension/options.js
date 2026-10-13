/* The options page: the address of the application, the switches, the language. */

(async () => {
  const settings = await loadSettings();
  setLanguage(settings.language);
  translatePage();
  document.getElementById("server").value = settings.server;
  document.getElementById("enabled").checked = settings.enabled;
  document.getElementById("decorate").checked = settings.decorate;
  document.getElementById("language").value = settings.language;
})();

document.getElementById("save").addEventListener("click", async () => {
  const server = document.getElementById("server").value.trim() || DEFAULT_SERVER;
  const language = document.getElementById("language").value;
  await saveSettings({
    server,
    enabled: document.getElementById("enabled").checked,
    decorate: document.getElementById("decorate").checked,
    language,
  });
  await browser.runtime.sendMessage({ type: "patterns-changed" });
  // The page speaks the language chosen at once
  setLanguage(language);
  translatePage();
  document.getElementById("saved").textContent = t("options.saved");
});
