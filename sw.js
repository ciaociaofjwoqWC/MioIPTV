// Service worker minimo: serve solo a rendere l'app installabile.
// Non mette in cache i flussi video: quelli devono sempre arrivare dalla rete.
self.addEventListener('install', (e) => self.skipWaiting());
self.addEventListener('activate', (e) => self.clients.claim());
self.addEventListener('fetch', () => {}); // passa tutto direttamente alla rete
