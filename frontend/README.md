# Document control development UI

React/Vite forms for creating and reading documents and reading audit events.
The current development UI calls the document service on port 8083 and the audit
service on port 8084 directly. Gateway routing and authentication remain WIP.

Use Node.js 24 (24.15 or later) and npm:

```bash
cd frontend
npm ci
npm run dev
```

Validation:

```bash
npm test
npm run lint
npm run build
```

`npm run test:watch` runs Vitest interactively. React Testing Library/user-event
tests cover form payloads, loading/duplicate-submit behavior, rendered results,
failure recovery and clearing stale results. API tests cover the fetch contract,
response formats, service messages and network failures. Tests run in jsdom and
mock the API boundary or fetch; they do not start backend services or certify a
deployed browser-to-database flow.
