// Service worker: makes the app open with no connection.
const VERSION = 'v2';
const SHELL = 'shell-' + VERSION;
const RUNTIME = 'runtime-' + VERSION; // map tiles + photos seen while online
const RUNTIME_MAX = 600;

const SHELL_FILES = [
  './', 'index.html', 'styles.css', 'app.js', 'config.js', 'manifest.webmanifest',
  'vendor/leaflet.js', 'vendor/leaflet.css', 'vendor/supabase.js',
  'icons/icon-192.png', 'icons/icon-512.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(SHELL_FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => ![SHELL, RUNTIME].includes(k)).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

async function trim(cache) {
  const keys = await cache.keys();
  for (let i = 0; i < keys.length - RUNTIME_MAX; i++) await cache.delete(keys[i]);
}

async function cacheFirst(req) {
  const cache = await caches.open(RUNTIME);
  const hit = await cache.match(req);
  if (hit) return hit;
  const res = await fetch(req);
  if (res.ok) { cache.put(req, res.clone()); trim(cache); }
  return res;
}

async function staleWhileRevalidate(req) {
  const cache = await caches.open(SHELL);
  const hit = await cache.match(req, { ignoreSearch: req.mode === 'navigate' });
  const fresh = fetch(req).then((res) => { if (res.ok) cache.put(req, res.clone()); return res; });
  if (hit) { fresh.catch(() => {}); return hit; }
  try { return await fresh; }
  catch (err) {
    if (req.mode === 'navigate') { const page = await cache.match('index.html'); if (page) return page; }
    throw err;
  }
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  const isTile = url.hostname === 'tile.openstreetmap.org';
  const isPhoto = url.hostname.endsWith('.supabase.co') && url.pathname.startsWith('/storage/v1/object/public/');
  if (isTile || isPhoto) { e.respondWith(cacheFirst(req)); return; }
  if (url.origin === self.location.origin) { e.respondWith(staleWhileRevalidate(req)); return; }
  // everything else (Supabase database + sign-in) always goes to the network
});
