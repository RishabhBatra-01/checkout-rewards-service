# frontend

Next.js client for the checkout and rewards service. See the
[repository README](../README.md) for the full description, routes and proxy setup.

Start the Spring Boot backend first, then:

```bash
npm install
npm run dev          # http://localhost:3000
```

`next.config.ts` rewrites `/api/*` to `http://localhost:8080/api/*`, so the browser
stays same-origin and the backend needs no CORS configuration. Override the target
with `BACKEND_URL`.
