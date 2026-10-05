// Higgsfield "model profiles": which model endpoint to call and which settings it accepts.
//
// IMPORTANT: Higgsfield's docs site was not reachable while this was built, so only ONE
// model is pre-filled, taken from the example in Higgsfield's official Python SDK readme.
// Add the models you want in Settings -> Higgsfield models, copying the endpoint path and
// the allowed values from the model's page on docs.higgsfield.ai.
//
// A profile looks like:
// {
//   id: "seedream-v4",                         // any unique short name
//   name: "Seedream v4",                       // shown in the dropdown
//   type: "image" | "video",
//   path: "bytedance/seedream/v4/text-to-image", // the part after https://api.higgsfield.ai/
//   options: { aspect_ratio: ["16:9"], resolution: ["2K"] }, // dropdowns shown on the main screen
//   fixed: { },                                 // extra settings always sent as-is
//   price: 0.00                                 // YOUR estimate in USD per generation (for the cost estimate)
// }

export const DEFAULT_MODELS = [
  {
    id: 'seedream-v4',
    name: 'Seedream v4 (from Higgsfield SDK example)',
    type: 'image',
    path: 'bytedance/seedream/v4/text-to-image',
    options: { aspect_ratio: ['16:9'], resolution: ['2K'] },
    fixed: {},
    price: null,
  },
];

/** Check a profile typed in Settings. Returns an error message or "" if it's fine. */
export function validateModel(model) {
  if (!model || typeof model !== 'object') return 'Model must be an object.';
  if (!model.id || !/^[a-z0-9._-]+$/i.test(model.id)) return 'id: letters, numbers, dot, dash or underscore only.';
  if (!model.name) return 'name is required.';
  if (!['image', 'video'].includes(model.type)) return 'type must be "image" or "video".';
  if (!model.path || /^https?:/i.test(model.path) || /\s/.test(model.path)) {
    return 'path is the part after https://api.higgsfield.ai/ (no spaces, no "https://").';
  }
  if (model.options && (typeof model.options !== 'object' || Array.isArray(model.options))) {
    return 'options must be an object like { "aspect_ratio": ["16:9", "9:16"] }.';
  }
  for (const [key, values] of Object.entries(model.options || {})) {
    if (!Array.isArray(values) || !values.length) return `options.${key} must be a non-empty list.`;
  }
  if (model.fixed && (typeof model.fixed !== 'object' || Array.isArray(model.fixed))) return 'fixed must be an object.';
  if (model.price != null && !(Number(model.price) >= 0)) return 'price must be a number (USD per generation) or empty.';
  return '';
}
