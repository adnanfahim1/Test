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
//   price: 0.00,                                // YOUR estimate in USD per generation (for the cost estimate)
//   imageField: "input_images",                 // OPTIONAL: name of the setting that takes your reference image
//   imageFormat: "list"                         // how that setting wants the image (see IMAGE_FORMATS below)
// }
//
// Different Higgsfield models take the image in different shapes. Examples from Higgsfield's
// official SDK: DoP image-to-video uses  input_images: [{ type: "image_url", image_url: "<url>" }]
// (format "list"), Soul uses  image_reference: { type: "image_url", image_url: "<url>" } (format "object").
// Check the model's docs page for its field name and shape.

// Built-in models. These are always available and are updated with each PikGen version.
// "source" says where each one's settings come from, so you know how sure they are.
export const DEFAULT_MODELS = [
  {
    id: 'qwen-image-3-edit',
    name: 'Qwen Image 3 Edit (edit your pictures)',
    type: 'image',
    path: 'alibaba/qwen-image-3/edit',
    options: { aspect_ratio: ['1:1'], resolution: ['1k'] },
    fixed: {},
    price: null,
    imageField: 'image_urls',
    imageFormat: 'url_list',
    requiresImage: true,
    source: 'Higgsfield docs example',
  },
  {
    id: 'genjutsu-motion',
    name: 'Genjutsu Motion Transfer (picture + motion video -> video)',
    type: 'video',
    path: 'higgsfield/genjutsu/motion-transfer/v1.0',
    options: {},
    fixed: {},
    price: null,
    imageField: 'image_urls',
    imageFormat: 'url_list',
    requiresImage: true,
    noPrompt: true,
    videoField: 'video_url',
    source: 'Higgsfield docs example',
  },
  {
    id: 'dop-turbo-i2v',
    name: 'DoP Turbo (picture -> video)',
    type: 'video',
    path: 'v1/image2video/dop',
    options: {},
    fixed: { model: 'dop-turbo' },
    price: null,
    imageField: 'input_images',
    imageFormat: 'list',
    requiresImage: true,
    source: 'Higgsfield official SDK example (may be an older endpoint)',
  },
  {
    id: 'seedream-v4',
    name: 'Seedream v4 (text -> image)',
    type: 'image',
    path: 'bytedance/seedream/v4/text-to-image',
    options: { aspect_ratio: ['16:9'], resolution: ['2K'] },
    fixed: {},
    price: null,
    source: 'Higgsfield official SDK example',
  },
  {
    id: 'kling-3-std-t2v',
    name: 'Kling 3.0 Standard (text -> video, unverified)',
    type: 'video',
    path: 'kling-video/v3.0/std/text-to-video',
    options: {},
    fixed: {},
    price: null,
    source: 'Endpoint seen in Higgsfield docs search results only; extra settings unknown',
  },
  {
    id: 'wan-3-prime-t2v',
    name: 'Wan 3.0 Prime (text -> video, unverified)',
    type: 'video',
    path: 'alibaba/wan-3.0-prime/text-to-video',
    options: {},
    fixed: {},
    price: null,
    source: 'Endpoint seen in Higgsfield docs search results only; extra settings unknown',
  },
  {
    id: 'happy-horse-t2v',
    name: 'Happy Horse 1.0 (text -> video, unverified)',
    type: 'video',
    path: 'alibaba/happy-horse/text-to-video',
    options: {},
    fixed: {},
    price: null,
    source: 'Endpoint seen in Higgsfield docs search results only; extra settings unknown',
  },
];

const BUILT_IN_IDS = new Set(DEFAULT_MODELS.map((m) => m.id));
export const isBuiltIn = (id) => BUILT_IN_IDS.has(id);

/**
 * The model list you see: built-in models (minus ones you hid), with your own edits on top,
 * plus models you added yourself.
 */
export function mergeModels(customModels = [], hiddenIds = []) {
  const custom = new Map(customModels.map((m) => [m.id, m]));
  const builtIns = DEFAULT_MODELS.filter((m) => !hiddenIds.includes(m.id)).map((m) => ({
    ...m,
    ...(custom.get(m.id) || {}),
    builtIn: true,
  }));
  const extra = customModels.filter((m) => !BUILT_IN_IDS.has(m.id));
  return [...builtIns, ...extra];
}

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
  if (model.videoField && !/^[a-z0-9_]+$/i.test(model.videoField)) return 'video field: letters, numbers and underscore only.';
  if (model.fixed && (typeof model.fixed !== 'object' || Array.isArray(model.fixed))) return 'fixed must be an object.';
  if (model.price != null && !(Number(model.price) >= 0)) return 'price must be a number (USD per generation) or empty.';
  if (model.imageField && !/^[a-z0-9_]+$/i.test(model.imageField)) return 'image field: letters, numbers and underscore only.';
  if (model.imageField && !(model.imageFormat in IMAGE_FORMATS)) return 'image format must be one of: ' + Object.keys(IMAGE_FORMATS).join(', ');
  return '';
}

/** The shapes a model can want the reference image in. */
export const IMAGE_FORMATS = {
  url: 'plain link:  "image": "https://..."',
  object: 'object:  "image": { "type": "image_url", "image_url": "https://..." }',
  list: 'list of objects:  "image": [{ "type": "image_url", "image_url": "https://..." }]',
  url_list: 'list of links:  "image": ["https://..."]',
};

/** Does this model accept a reference image? */
export function acceptsImage(model) {
  return Boolean(model?.imageField);
}

/** Build the image setting for one request, e.g. { input_images: [{ type: "image_url", image_url }] }. */
export function imageParam(field, format, url) {
  if (!field || !url) return {};
  const obj = { type: 'image_url', image_url: url };
  const value = { url, object: obj, list: [obj], url_list: [url] }[format || 'url'] ?? url;
  return { [field]: value };
}
