// Small helpers shared by the rest of the extension.
// Everything here is "pure" (no browser APIs), so it can be unit-tested in Node.

/** Turn any text into a short, filename-safe slug: "A Red Fox!" -> "a-red-fox" */
export function slugify(text, maxLength = 40) {
  const slug = String(text ?? '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '') // strip accents
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-') // anything not a-z/0-9 becomes a dash
    .replace(/^-+|-+$/g, '');
  const cut = slug.slice(0, maxLength).replace(/-+$/g, '');
  return cut || 'untitled';
}

/** 7 -> "007" */
export function pad(number, width = 3) {
  return String(number).padStart(width, '0');
}

/** Date as YYYY-MM-DD in the user's local time zone. */
export function dateStamp(date = new Date()) {
  const y = date.getFullYear();
  const m = pad(date.getMonth() + 1, 2);
  const d = pad(date.getDate(), 2);
  return `${y}-${m}-${d}`;
}

/** Folder name for a batch: <batch-name>_<date> */
export function batchFolderName(batchName, date = new Date()) {
  return `${slugify(batchName, 40)}_${dateStamp(date)}`;
}

/**
 * File name for one result:
 * <batch-name>_<number>_<short-prompt-slug>.<ext>
 * If one request returns several files, the 2nd+ get "-2", "-3"... after the number.
 */
export function resultFileName(batchName, number, prompt, ext, index = 0) {
  const suffix = index > 0 ? `-${index + 1}` : '';
  return `${slugify(batchName, 30)}_${pad(number)}${suffix}_${slugify(prompt, 40)}.${ext}`;
}

const EXT_BY_TYPE = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/jpg': 'jpg',
  'image/webp': 'webp',
  'image/gif': 'gif',
  'image/avif': 'avif',
  'video/mp4': 'mp4',
  'video/webm': 'webm',
  'video/quicktime': 'mov',
};

/** Pick a file extension from the server's content-type, then the URL, then a default. */
export function pickExtension(contentType, url, kind) {
  const type = String(contentType || '').split(';')[0].trim().toLowerCase();
  if (EXT_BY_TYPE[type]) return EXT_BY_TYPE[type];
  try {
    const path = new URL(url).pathname;
    const match = path.match(/\.([a-z0-9]{2,5})$/i);
    if (match) {
      const ext = match[1].toLowerCase();
      if (['png', 'jpg', 'jpeg', 'webp', 'gif', 'avif', 'mp4', 'webm', 'mov'].includes(ext)) {
        return ext === 'jpeg' ? 'jpg' : ext;
      }
    }
  } catch {
    // not a valid URL - fall through to default
  }
  return kind === 'video' ? 'mp4' : 'png';
}

/** Escape one CSV cell (quotes, commas, new lines). */
export function csvCell(value) {
  const text = value == null ? '' : String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** Build a CSV document from a header array and row arrays. */
export function toCsv(header, rows) {
  return [header, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

/** Random number in [0, max) - used to spread out polling so requests don't all fire at once. */
export function jitter(maxMs) {
  return Math.floor(Math.random() * maxMs);
}

/** Exponential backoff: 5s, 10s, 20s, ... capped. */
export function backoffDelay(attempt, baseMs = 5000, capMs = 120000) {
  return Math.min(capMs, baseMs * 2 ** Math.max(0, attempt));
}

/** Hide secrets if they ever end up inside an error message. */
export function redact(text, secrets = []) {
  let out = String(text ?? '');
  for (const secret of secrets) {
    if (secret && secret.length >= 6) out = out.split(secret).join('[hidden]');
  }
  return out;
}
