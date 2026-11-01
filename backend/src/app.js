import http from "node:http";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { planCommand } from "./planner.js";
import { ProviderError } from "./ai.js";

const MAX_BODY = 16 * 1024;
const MAX_MESSAGE = 2000;
const MAX_HISTORY = 20;
const LANGUAGES = new Set(["English", "Urdu", "English + Urdu"]);

/**
 * Parse "token:role,token2:role2". Role defaults to "user". Roles: user | admin.
 */
export function parseTokens(raw) {
  const map = new Map();
  for (const part of String(raw || "").split(",").map((s) => s.trim()).filter(Boolean)) {
    const i = part.lastIndexOf(":");
    const token = i > 0 ? part.slice(0, i) : part;
    const role = i > 0 ? part.slice(i + 1) : "user";
    if (token.length >= 16 && (role === "user" || role === "admin")) map.set(token, role);
  }
  return map;
}

function safeEqual(a, b) {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

function send(res, status, body, extra = {}) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    ...extra,
  });
  res.end(payload);
}

function fail(res, status, code, message, requestId, extra) {
  send(res, status, { error: { code, message }, requestId }, extra);
}

async function readJson(req) {
  const type = String(req.headers["content-type"] || "").toLowerCase();
  if (!type.startsWith("application/json")) return { err: [415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json"] };
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > MAX_BODY) return { err: [413, "PAYLOAD_TOO_LARGE", "Request body too large"] };
    chunks.push(c);
  }
  try {
    const v = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (v === null || typeof v !== "object" || Array.isArray(v)) return { err: [400, "INVALID_JSON", "Body must be a JSON object"] };
    return { value: v };
  } catch {
    return { err: [400, "INVALID_JSON", "Body is not valid JSON"] };
  }
}

function validateChat(b) {
  if (typeof b.message !== "string" || b.message.trim() === "") return "message is required and must be a non-empty string";
  if (b.message.length > MAX_MESSAGE) return `message must be at most ${MAX_MESSAGE} characters`;
  if (b.language !== undefined && !LANGUAGES.has(b.language)) return "language must be one of: English, Urdu, English + Urdu";
  if (b.history !== undefined) {
    if (!Array.isArray(b.history) || b.history.length > MAX_HISTORY) return `history must be an array of at most ${MAX_HISTORY} items`;
    for (const h of b.history) {
      if (!h || (h.role !== "user" && h.role !== "assistant") || typeof h.content !== "string" || h.content.length > MAX_MESSAGE) {
        return "history items must be {role: 'user'|'assistant', content: string}";
      }
    }
  }
  return null;
}

/**
 * createApp builds the HTTP server. Everything environment-specific is injected
 * so tests exercise the real request pipeline without network access.
 */
export function createApp({ env = {}, provider, log = () => {}, now = () => Date.now() }) {
  const tokens = parseTokens(env.RAZA_API_TOKENS);
  const production = env.NODE_ENV === "production";
  const limit = Number(env.RATE_LIMIT_PER_MIN || 30);
  const startedAt = now();
  const hits = new Map();

  function rateLimited(key) {
    const t = now();
    const win = Math.floor(t / 60000);
    const rec = hits.get(key);
    if (!rec || rec.win !== win) {
      hits.set(key, { win, n: 1 });
      if (hits.size > 5000) for (const [k, v] of hits) if (v.win !== win) hits.delete(k);
      return 0;
    }
    rec.n += 1;
    return rec.n > limit ? Math.max(1, 60 - Math.floor((t % 60000) / 1000)) : 0;
  }

  /** returns {role} or sends an error and returns null */
  function authenticate(req, res, requestId) {
    if (tokens.size === 0) {
      if (production) {
        fail(res, 503, "AUTH_NOT_CONFIGURED", "Server authentication is not configured", requestId);
        return null;
      }
      return { role: "admin", dev: true }; // open only outside production
    }
    const m = /^Bearer (.+)$/.exec(String(req.headers.authorization || ""));
    if (m) {
      for (const [tok, role] of tokens) if (safeEqual(m[1], tok)) return { role };
    }
    fail(res, 401, "UNAUTHORIZED", "Missing or invalid credentials", requestId, { "www-authenticate": "Bearer" });
    return null;
  }

  const server = http.createServer(async (req, res) => {
    const requestId = randomUUID();
    const started = now();
    res.on("finish", () => log({ requestId, method: req.method, path: req.url?.split("?")[0], status: res.statusCode, ms: now() - started }));
    try {
      const url = (req.url || "/").split("?")[0];

      if (req.method === "GET" && url === "/api/v1/health") {
        return send(res, 200, { status: "ok", service: "raza-ai-backend", version: "1.0.0", time: new Date(now()).toISOString(), requestId });
      }

      const known = ["/api/v1/assistant", "/api/v1/command-plan", "/api/v1/diagnostics"];
      if (!known.includes(url)) return fail(res, 404, "NOT_FOUND", "Unknown endpoint", requestId);

      const expected = url === "/api/v1/diagnostics" ? "GET" : "POST";
      if (req.method !== expected) return fail(res, 405, "METHOD_NOT_ALLOWED", `Use ${expected}`, requestId, { allow: expected });

      const principal = authenticate(req, res, requestId);
      if (!principal) return;

      const key = `${req.socket.remoteAddress}|${String(req.headers.authorization || "").slice(-8)}`;
      const retry = rateLimited(key);
      if (retry) return fail(res, 429, "RATE_LIMITED", "Too many requests", requestId, { "retry-after": String(retry) });

      if (url === "/api/v1/diagnostics") {
        if (principal.role !== "admin") return fail(res, 403, "FORBIDDEN", "Admin role required", requestId);
        return send(res, 200, {
          uptimeSeconds: Math.round((now() - startedAt) / 1000),
          providerConfigured: Boolean(env.ANTHROPIC_API_KEY),
          authConfigured: tokens.size > 0,
          rateLimitPerMinute: limit,
          requestId,
        });
      }

      const parsed = await readJson(req);
      if (parsed.err) return fail(res, parsed.err[0], parsed.err[1], parsed.err[2], requestId);
      const body = parsed.value;
      const bad = validateChat(body);
      if (bad) return fail(res, 400, "VALIDATION_ERROR", bad, requestId);
      const language = body.language || "English + Urdu";

      if (url === "/api/v1/command-plan") {
        const plan = planCommand(body.message);
        // Defence in depth: the client must confirm every sensitive action.
        for (const a of plan.actions) if (a.sensitive) a.requiresConfirmation = true;
        return send(res, 200, { plan, executedByBackend: false, requestId });
      }

      // /api/v1/assistant
      try {
        const reply = await provider.reply({ message: body.message.trim(), language, history: body.history || [] });
        return send(res, 200, { reply, mode: "cloud", requestId });
      } catch (e) {
        if (e instanceof ProviderError) {
          const status = e.code === "PROVIDER_TIMEOUT" ? 504 : e.code === "PROVIDER_UNAVAILABLE" ? 503 : 502;
          return fail(res, status, e.code, e.message, requestId);
        }
        throw e;
      }
    } catch {
      if (!res.headersSent) fail(res, 500, "INTERNAL_ERROR", "Unexpected server error", requestId);
    }
  });

  return server;
}
