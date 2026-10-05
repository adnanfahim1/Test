// Small pure helpers (same naming rules as the PikGen extension).

export function slugify(text, maxLength = 40) {
  const slug = String(text ?? '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug.slice(0, maxLength).replace(/-+$/g, '') || 'untitled';
}

export const pad = (n, width = 3) => String(n).padStart(width, '0');

export function dateStamp(date = new Date()) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1, 2)}-${pad(date.getDate(), 2)}`;
}

/** <batch>_<number>_<prompt-slug>.<ext>; extra files from one job get -2, -3 ... */
export function resultFileName(batchName, number, prompt, ext, index = 0) {
  const suffix = index > 0 ? `-${index + 1}` : '';
  return `${slugify(batchName, 30)}_${pad(number)}${suffix}_${slugify(prompt, 40)}.${ext}`;
}

const EXT_BY_TYPE = {
  'image/png': 'png', 'image/jpeg': 'jpg', 'image/jpg': 'jpg', 'image/webp': 'webp', 'image/gif': 'gif',
  'image/avif': 'avif', 'video/mp4': 'mp4', 'video/webm': 'webm', 'video/quicktime': 'mov',
};
export const TYPE_BY_EXT = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', mp4: 'video/mp4', mov: 'video/quicktime', webm: 'video/webm' };

export function pickExtension(contentType, url, kind) {
  const type = String(contentType || '').split(';')[0].trim().toLowerCase();
  if (EXT_BY_TYPE[type]) return EXT_BY_TYPE[type];
  try {
    const m = new URL(url).pathname.match(/\.([a-z0-9]{2,5})$/i);
    if (m) {
      const ext = m[1].toLowerCase() === 'jpeg' ? 'jpg' : m[1].toLowerCase();
      if (Object.values(EXT_BY_TYPE).includes(ext)) return ext;
    }
  } catch {
    /* not a URL */
  }
  return kind === 'video' ? 'mp4' : 'png';
}

export function csvCell(value) {
  const text = value == null ? '' : String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}
export const toCsv = (header, rows) => [header, ...rows].map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
