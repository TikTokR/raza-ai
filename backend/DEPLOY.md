# Deploy the RAZA backend (HTTPS) and connect the app

Not deployed by default: someone has to host it. These steps work on any host that runs a Docker image or Node 20+ and gives you an HTTPS address (Render, Fly.io, Railway, a VPS behind Caddy/nginx, ...). They were not run on a specific host by the author, so use `verify-deploy.mjs` below as the real test.

## 1. Make your tokens (on your own computer)
```
cd backend && node scripts/make-token.mjs
```
It prints a PHONE token (role user) and a `RAZA_API_TOKENS=` line. The line contains an admin token as well; that one stays on the server.

## 2. Set these on the host (Environment / Secrets, never in git)
| Name | Value |
|---|---|
| `RAZA_API_TOKENS` | the line printed above (`phonetoken:user,admintoken:admin`) |
| `ANTHROPIC_API_KEY` | your own provider key (server only) |
| `NODE_ENV` | `production` |
| `AI_PROVIDER`, `OPENAI_API_KEY`, `OPENAI_MODEL` | optional: use OpenAI instead of Anthropic (`AI_PROVIDER=openai`, your own key, model default `gpt-4o-mini`). Set only one provider's key. |

Build with `backend/Dockerfile` (listens on `$PORT`, default 8080). Or run `node src/server.js` from `backend/`. Point the host's health check at `/api/v1/health`. In production the server refuses requests if `RAZA_API_TOKENS` is empty.

## 3. Verify against the real host
```
RAZA_TOKEN=<phone token> node scripts/verify-deploy.mjs https://YOUR-HOST
```
All five checks must PASS: HTTPS health, 401 without token, token accepted, token is NOT admin, real AI reply.

## 4. Put in the app (Settings > Online / Offline)
- **Backend address (HTTPS)**: `https://YOUR-HOST`, only the origin. No spaces, no username, no path. A trailing `/` or `/api/v1` is removed for you. `http://` is rejected.
- **Access token**: the PHONE token, 48 hex characters. Never the admin token and never the provider key.
Tap "Save & test connection". The status should read online.

Free host tiers may sleep when idle, so the first request can be slow or time out (the app then says so). A leaked phone token can be revoked by changing it in `RAZA_API_TOKENS` and redeploying.
