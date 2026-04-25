// Same-origin in both modes:
//   dev  — `ng serve` proxies /api and /ws via proxy.conf.json (no CORS).
//   prod — nginx in docker-compose terminates same-origin and reverse-proxies
//          /api and /ws to the backend.
// Use Angular's `isDevMode()` when dev-vs-prod branching is actually needed.
export const environment = {
  apiBaseUrl: '',
  wsBaseUrl: '',
} as const;
