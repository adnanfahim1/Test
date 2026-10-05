// Small wrapper around IndexedDB (the browser's built-in database).
// Stores: batches (so a batch survives a browser restart) and "kv" (the saved folder handle).

const DB_NAME = 'bulk-gen';
const DB_VERSION = 1;
let dbPromise = null;

function open() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('batches')) db.createObjectStore('batches', { keyPath: 'id' });
        if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

async function run(storeName, mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, mode);
    const result = fn(tx.objectStore(storeName));
    tx.oncomplete = () => resolve(result?.result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

export const kvGet = (key) => run('kv', 'readonly', (s) => s.get(key));
export const kvSet = (key, value) => run('kv', 'readwrite', (s) => s.put(value, key));

// Batches are plain objects; we drop anything that can't be stored (e.g. timers).
export const saveBatch = (batch) => run('batches', 'readwrite', (s) => s.put(JSON.parse(JSON.stringify(batch))));
export const getBatch = (id) => run('batches', 'readonly', (s) => s.get(id));
export const deleteBatch = (id) => run('batches', 'readwrite', (s) => s.delete(id));
export const listBatches = async () => {
  const all = (await run('batches', 'readonly', (s) => s.getAll())) || [];
  return all.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
};
