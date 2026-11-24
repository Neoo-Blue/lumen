// Lumen service worker: the app shell opens instantly (and offline); everything the person asks always goes live.
const SHELL = 'lumen-shell-v1';
self.addEventListener('install', e => { self.skipWaiting(); e.waitUntil(caches.open(SHELL).then(c => c.addAll(['/', '/icons/icon-192.png', '/manifest.webmanifest']))); });
self.addEventListener('activate', e => e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== SHELL).map(k => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== location.origin) return;
  if (u.pathname === '/') { // network first, the cached shell when offline
    e.respondWith(fetch(e.request).then(r => { const copy = r.clone(); caches.open(SHELL).then(c => c.put('/', copy)); return r; }).catch(() => caches.match('/')));
    return;
  }
  if (u.pathname.startsWith('/icons/') || u.pathname === '/manifest.webmanifest') e.respondWith(caches.match(e.request).then(r => r || fetch(e.request)));
});
