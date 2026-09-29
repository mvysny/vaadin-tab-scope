// Exposes the browser's own Duplicate Tab to the driver: chrome.tabs.duplicate is what the
// tab-strip context menu calls, and CDP has no equivalent.
self.duplicateTab = async (urlPattern) => {
  const [tab] = await chrome.tabs.query({ url: urlPattern });
  const dup = await chrome.tabs.duplicate(tab.id);
  return dup.id;
};
