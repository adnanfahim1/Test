// Settings live in chrome.storage.local: only this extension on this computer can read them.
// API keys are never logged and only sent to their own official API.

import { DEFAULT_MODELS } from './models.js';

export const DEFAULT_SETTINGS = {
  anthropicKey: '',
  higgsfieldKey: '', // "KEY_ID:KEY_SECRET"
  claudeModel: 'claude-opus-5-5',
  defaultImageModel: 'seedream-v4',
  defaultVideoModel: '',
  concurrency: 2, // how many generations run at the same time
  models: DEFAULT_MODELS,
  // Only used by automated tests (pointing at a fake server). Leave empty.
  anthropicBaseUrl: '',
  higgsfieldBaseUrl: '',
};

export async function loadSettings() {
  const stored = await chrome.storage.local.get('settings');
  return { ...DEFAULT_SETTINGS, ...(stored.settings || {}) };
}

export async function saveSettings(patch) {
  const current = await loadSettings();
  const next = { ...current, ...patch };
  await chrome.storage.local.set({ settings: next });
  return next;
}
