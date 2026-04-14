// Service Worker — l'Amicale du WiFi
const CACHE = 'amicale-v1';
const OFFLINE_URL = '/mobile/';

// Ressources à mettre en cache au premier chargement
const PRECACHE = [
  '/css/amicale.css',
  '/manifest.json',
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c => c.addAll(PRECACHE)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  // Ne pas intercepter les requêtes non-GET ni les API
  if (e.request.method !== 'GET') return;
  if (e.request.url.includes('/api/') || e.request.url.includes('/qr/')) return;

  e.respondWith(
    fetch(e.request)
      .then(resp => {
        // Mettre en cache le CSS et autres assets statiques
        if (e.request.url.includes('/css/') || e.request.url.includes('/js/')) {
          const clone = resp.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return resp;
      })
      .catch(() => caches.match(e.request).then(r => r || caches.match(OFFLINE_URL)))
  );
});
