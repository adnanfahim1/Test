// Background service worker. Its only job: clicking the toolbar icon opens the
// PikGen tab (or switches to it if it's already open).
// All the real work happens in the Studio tab, and only after you press Start.

const STUDIO_URL = chrome.runtime.getURL('ui/studio.html');

chrome.action.onClicked.addListener(async () => {
  // runtime.getContexts finds our own open Studio tab without needing the "tabs" permission.
  try {
    if (chrome.runtime.getContexts) {
      const contexts = await chrome.runtime.getContexts({ contextTypes: ['TAB'] });
      const studio = contexts.find((c) => c.documentUrl?.startsWith(STUDIO_URL) && c.tabId >= 0);
      if (studio) {
        await chrome.tabs.update(studio.tabId, { active: true });
        if (studio.windowId >= 0) await chrome.windows.update(studio.windowId, { focused: true });
        return;
      }
    }
  } catch {
    // older browser: just open a new tab below
  }
  await chrome.tabs.create({ url: STUDIO_URL });
});
