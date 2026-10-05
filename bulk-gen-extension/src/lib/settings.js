// Settings live in chrome.storage.local: only this extension on this computer can read them.
// API keys are never logged and only sent to their own official API.
//
// Models: the built-in list comes from models.js (so updates to PikGen bring new models).
// Only YOUR changes are stored: models you added or edited (customModels) and
// built-in models you deleted (hiddenModels).

import { mergeModels, isBuiltIn } from './models.js';

export const DEFAULT_SETTINGS = {
  anthropicKey: '',
  higgsfieldKey: '', // "KEY_ID:KEY_SECRET"
  claudeModel: 'claude-opus-5-5',
  defaultImageModel: 'seedream-v4', // text -> image; loading pictures switches to a picture model automatically
  defaultVideoModel: '',
  concurrency: 2, // how many generations run at the same time
  customModels: [],
  hiddenModels: [],
  // Only used by automated tests (pointing at a fake server). Leave empty.
  anthropicBaseUrl: '',
  higgsfieldBaseUrl: '',
};

export async function loadSettings() {
  const stored = (await chrome.storage.local.get('settings')).settings || {};
  const settings = { ...DEFAULT_SETTINGS, ...stored };
  // Older versions stored the whole model list: keep only models the user added.
  if (Array.isArray(stored.models) && !stored.customModels) {
    settings.customModels = stored.models.filter((m) => !isBuiltIn(m.id));
  }
  delete settings.models;
  settings.models = mergeModels(settings.customModels, settings.hiddenModels);
  return settings;
}

export async function saveSettings(patch) {
  const current = await loadSettings();
  const next = { ...current, ...patch };
  delete next.models; // computed on load, never stored
  await chrome.storage.local.set({ settings: next });
  return loadSettings();
}

/** Add a new model, or save your edits to an existing one (built-in or not). */
export async function saveModel(model) {
  const current = await loadSettings();
  const customModels = [...current.customModels.filter((m) => m.id !== model.id), model];
  const hiddenModels = current.hiddenModels.filter((id) => id !== model.id);
  return saveSettings({ customModels, hiddenModels });
}

/** Delete a model (built-in models are hidden; "Restore built-in models" brings them back). */
export async function deleteModel(id) {
  const current = await loadSettings();
  const customModels = current.customModels.filter((m) => m.id !== id);
  const hiddenModels = isBuiltIn(id) ? [...new Set([...current.hiddenModels, id])] : current.hiddenModels;
  return saveSettings({ customModels, hiddenModels });
}

/** Bring back all built-in models and undo your edits to them. */
export async function restoreBuiltInModels() {
  const current = await loadSettings();
  return saveSettings({ customModels: current.customModels.filter((m) => !isBuiltIn(m.id)), hiddenModels: [] });
}
