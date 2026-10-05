// Talks to the Higgsfield API.
//
// Everything below comes from Higgsfield's OFFICIAL SDK source code
// (@higgsfield/client v0.2.6 on npm, higgsfield-client v0.2.0 on PyPI):
//   - Base URL: https://api.higgsfield.ai
//   - Auth header: "Authorization: Key KEY_ID:KEY_SECRET"
//   - Start a generation:  POST /<model path>   (JSON body = the model's settings)
//       -> { request_id, status, status_url, cancel_url }
//   - Check on it:         GET  /requests/<request_id>/status
//       -> { status: queued | in_progress | completed | failed | nsfw | canceled,
//            images: [{ url }], video: { url } }
//   - Cancel (only before it starts processing): POST /requests/<request_id>/cancel
//   - Upload link (used here only to test your key): POST /files/generate-upload-url
//   - Errors: 401 = bad key, 403 = not enough credits, 400/422 = bad input, 5xx = server problem
//
// Your key is only ever sent to api.higgsfield.ai, and never logged.

export const HIGGSFIELD_BASE_URL = 'https://api.higgsfield.ai';

/** An error from Higgsfield, sorted into a "kind" so the queue knows what to do. */
export class HiggsfieldError extends Error {
  /**
   * kind:
   *  - "auth"    bad key -> stop the batch
   *  - "credits" not enough credits -> stop the batch
   *  - "busy"    rate/concurrency limit -> wait and retry, lower parallel jobs
   *  - "server"  temporary server/network problem -> wait and retry
   *  - "input"   Higgsfield rejected the settings/prompt -> fail this item
   *  - "notfound" the request id is unknown
   */
  constructor(message, { status = 0, kind = 'server', retryAfterMs = 0 } = {}) {
    super(message);
    this.name = 'HiggsfieldError';
    this.status = status;
    this.kind = kind;
    this.retryAfterMs = retryAfterMs;
  }
}

/** Decide what kind of error an HTTP status + message is. Exported for tests. */
export function classifyError(status, message = '') {
  if (status === 401) return 'auth';
  if (status === 403) return 'credits';
  if (status === 429) return 'busy';
  if (status === 404) return 'notfound';
  if (status === 408 || status >= 500 || status === 0) return 'server';
  if (status === 400 && /concurren|too many|rate.?limit|limit (reached|exceeded)/i.test(message)) {
    // Higgsfield's docs (seen via search results only) say a 400 can also mean
    // "concurrency reached". We only treat it as "busy" if the message says so.
    return 'busy';
  }
  return 'input';
}

/** Read a Retry-After header ("5" seconds or an HTTP date) into milliseconds. */
function retryAfterMs(response) {
  const value = response.headers.get('retry-after');
  if (!value) return 0;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return Math.max(0, seconds * 1000);
  const when = Date.parse(value);
  return Number.isFinite(when) ? Math.max(0, when - Date.now()) : 0;
}

/** Pull a readable message out of an error response body. */
function errorMessage(data, fallback) {
  if (!data) return fallback;
  if (typeof data === 'string') return data.slice(0, 500);
  const detail = data.detail ?? data.details ?? data.message ?? data.error;
  if (typeof detail === 'string') return detail;
  if (detail) return JSON.stringify(detail).slice(0, 500);
  return fallback;
}

export class HiggsfieldClient {
  /**
   * @param {object} opts
   * @param {string} opts.credentials  "KEY_ID:KEY_SECRET" exactly as shown in the Higgsfield console
   * @param {string} [opts.baseURL]
   * @param {typeof fetch} [opts.fetchImpl]
   */
  constructor({ credentials, baseURL = HIGGSFIELD_BASE_URL, fetchImpl } = {}) {
    const parts = String(credentials || '').trim().split(':');
    if (parts.length !== 2 || !parts[0] || !parts[1]) {
      throw new HiggsfieldError('Higgsfield key must look like KEY_ID:KEY_SECRET (two parts joined by a colon).', {
        kind: 'auth',
      });
    }
    this.credentials = `${parts[0]}:${parts[1]}`;
    this.secret = parts[1];
    this.baseURL = baseURL.replace(/\/+$/, '');
    this.fetch = fetchImpl || globalThis.fetch.bind(globalThis);
  }

  async request(method, path, body) {
    const url = `${this.baseURL}/${String(path).replace(/^\/+/, '')}`;
    let response;
    try {
      response = await this.fetch(url, {
        method,
        headers: {
          Authorization: `Key ${this.credentials}`,
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (err) {
      // Network failure (offline, DNS, blocked). Never include the key in messages.
      throw new HiggsfieldError(`Could not reach Higgsfield (${err?.message || 'network error'}).`, {
        kind: 'server',
      });
    }

    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = text;
    }

    if (!response.ok) {
      const message = errorMessage(data, `HTTP ${response.status}`).split(this.secret).join('[hidden]');
      throw new HiggsfieldError(`Higgsfield error ${response.status}: ${message}`, {
        status: response.status,
        kind: classifyError(response.status, message),
        retryAfterMs: retryAfterMs(response),
      });
    }
    return data;
  }

  /** Start one generation. Returns { request_id, status, ... }. */
  async submit(modelPath, params) {
    const data = await this.request('POST', modelPath, params);
    if (!data || !data.request_id) {
      throw new HiggsfieldError('Higgsfield did not return a request id.', { kind: 'server' });
    }
    return data;
  }

  /** Check a generation. Returns the raw status object. */
  status(requestId) {
    return this.request('GET', `requests/${encodeURIComponent(requestId)}/status`);
  }

  /** Cancel a generation that has not started processing yet. */
  cancel(requestId) {
    return this.request('POST', `requests/${encodeURIComponent(requestId)}/cancel`);
  }

  /**
   * Upload one reference image (exactly like Higgsfield's official SDK does it):
   *  1) POST /files/generate-upload-url { content_type } -> { public_url, upload_url, upload_headers }
   *  2) PUT the file bytes to upload_url (no API key is sent there)
   * Returns public_url, which is then given to the model.
   */
  async uploadImage(blob) {
    const contentType = blob.type || 'image/jpeg';
    const data = await this.request('POST', 'files/generate-upload-url', { content_type: contentType });
    if (!data?.upload_url || !data?.public_url) {
      throw new HiggsfieldError('Higgsfield did not return an upload link.', { kind: 'server' });
    }
    let response;
    try {
      response = await this.fetch(data.upload_url, {
        method: 'PUT',
        headers: data.upload_headers || { 'Content-Type': contentType },
        body: blob,
      });
    } catch (err) {
      const error = new HiggsfieldError(`Could not upload the image (${err?.message || 'network error'}).`, { kind: 'server' });
      try {
        error.origin = new URL(data.upload_url).origin; // the extension may need permission for this site
      } catch {
        /* ignore */
      }
      throw error;
    }
    if (!response.ok) {
      throw new HiggsfieldError(`Image upload failed with HTTP ${response.status}.`, {
        status: response.status,
        kind: classifyError(response.status),
      });
    }
    return data.public_url;
  }

  /**
   * "Test connection": asks Higgsfield for an upload link. This proves the key works
   * without starting (or paying for) any generation.
   */
  async testConnection() {
    await this.request('POST', 'files/generate-upload-url', { content_type: 'image/png' });
    return true;
  }
}

/** Get every result URL out of a completed status response. */
export function resultUrls(statusData) {
  const urls = [];
  if (Array.isArray(statusData?.images)) {
    for (const image of statusData.images) if (image?.url) urls.push(image.url);
  }
  if (statusData?.video?.url) urls.push(statusData.video.url);
  if (Array.isArray(statusData?.videos)) {
    for (const video of statusData.videos) if (video?.url) urls.push(video.url);
  }
  return urls;
}
