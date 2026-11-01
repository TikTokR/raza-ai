# RAZA AI Backend API (v1)

Base path: `/api/v1`. All bodies are JSON (`Content-Type: application/json`, max 16 KB).
Protected endpoints need `Authorization: Bearer <token>`.

Every response includes `requestId`. Errors always have this shape:

```json
{ "error": { "code": "VALIDATION_ERROR", "message": "..." }, "requestId": "..." }
```

## GET /health (public)
`200 {"status":"ok","service":"raza-ai-backend","version":"1.0.0","time":"...","requestId":"..."}`

## POST /assistant (auth: user or admin)
Request: `{ "message": string (1-2000), "language"?: "English"|"Urdu"|"English + Urdu", "history"?: [{"role":"user"|"assistant","content":string}] (max 20) }`

Success: `200 {"reply": string, "mode": "cloud", "requestId": "..."}`

The backend never invents a reply. If the AI provider fails you get an error instead:

| Status | Code | Meaning |
|---|---|---|
| 503 | PROVIDER_UNAVAILABLE | no provider key configured on the server |
| 504 | PROVIDER_TIMEOUT | provider did not answer within `PROVIDER_TIMEOUT_MS` |
| 502 | PROVIDER_ERROR | provider returned an error / network failure |

## POST /command-plan (auth: user or admin)
Same request as `/assistant` (only `message` is used).

Success: `200 {"plan": {"intent": string, "confidence": number, "actions": [Action], "clarification"?: string}, "executedByBackend": false, "requestId": "..."}`

`Action`: `{ "type", "params", "summary", "sensitive": boolean, "requiresConfirmation": boolean }`

Types: `DIAL`, `PREPARE_SMS`, `SET_ALARM`, `VOLUME`, `MEDIA`, `OPEN_SETTINGS`, `OPEN_APP` (all sensitive, always `requiresConfirmation: true`) and `GET_TIME`, `GET_DATE`, `GET_BATTERY` (read-only).

**The backend only plans.** The Android app must show the action, ask YES/NO, and execute it itself.

## GET /diagnostics (auth: admin only)
`200 {"uptimeSeconds": n, "providerConfigured": bool, "authConfigured": bool, "rateLimitPerMinute": n, "requestId": "..."}`. Never returns secrets.

## Common errors
`400 INVALID_JSON | VALIDATION_ERROR`, `401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 NOT_FOUND`, `405 METHOD_NOT_ALLOWED`, `413 PAYLOAD_TOO_LARGE`, `415 UNSUPPORTED_MEDIA_TYPE`, `429 RATE_LIMITED` (with `Retry-After`), `500 INTERNAL_ERROR`, `503 AUTH_NOT_CONFIGURED` (production with no tokens).

## Configuration
See `.env.example`. In `NODE_ENV=production` the server refuses protected routes unless `RAZA_API_TOKENS` is set. Outside production with no tokens, protected routes are open for local development only.

## Logging
One JSON line per request: `requestId, method, path, status, ms`. Bodies, tokens and keys are never logged (covered by a test).

## Not implemented yet
Server-side persistence (users, conversation memory, tasks, preferences) and owner voice verification. On-device storage is used instead; see the app privacy notes.
