import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createApp, parseTokens } from "../src/app.js";
import { createAnthropicProvider, ProviderError } from "../src/ai.js";

const USER = "user-token-0123456789abcdef";
const ADMIN = "admin-token-0123456789abcdef";
const SECRET_KEY = "sk-test-SECRET-should-never-leak";

function start(opts = {}) {
  const logs = [];
  const server = createApp({
    env: { RAZA_API_TOKENS: `${USER}:user,${ADMIN}:admin`, ANTHROPIC_API_KEY: SECRET_KEY, RATE_LIMIT_PER_MIN: "1000", ...opts.env },
    provider: opts.provider || { reply: async ({ message }) => `echo: ${message}` },
    log: (e) => logs.push(e),
  });
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve({ server, logs, base: `http://127.0.0.1:${server.address().port}` })));
}
const stop = (s) => new Promise((r) => s.server.close(r));
const post = (base, path, body, token = USER, headers = {}) =>
  fetch(base + path, {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });

let ctx;
before(async () => { ctx = await start(); });
after(async () => { await stop(ctx); });

// ---------- health ----------
test("GET /api/v1/health is public and returns ok", async () => {
  const r = await fetch(ctx.base + "/api/v1/health");
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.status, "ok");
  assert.ok(j.requestId);
});

test("unknown route 404, wrong method 405", async () => {
  assert.equal((await fetch(ctx.base + "/api/v1/nope")).status, 404);
  const r = await fetch(ctx.base + "/api/v1/assistant", { headers: { authorization: `Bearer ${USER}` } });
  assert.equal(r.status, 405);
});

// ---------- authentication / authorization ----------
test("assistant rejects missing and wrong tokens with 401", async () => {
  assert.equal((await post(ctx.base, "/api/v1/assistant", { message: "hi" }, null)).status, 401);
  assert.equal((await post(ctx.base, "/api/v1/assistant", { message: "hi" }, "wrong-token-0123456789abcdef")).status, 401);
  assert.equal((await post(ctx.base, "/api/v1/command-plan", { message: "hi" }, null)).status, 401);
});

test("diagnostics: user role gets 403, admin gets 200, anonymous gets 401", async () => {
  const get = (t) => fetch(ctx.base + "/api/v1/diagnostics", { headers: t ? { authorization: `Bearer ${t}` } : {} });
  assert.equal((await get(null)).status, 401);
  assert.equal((await get(USER)).status, 403);
  const r = await get(ADMIN);
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.providerConfigured, true);
  assert.ok(!JSON.stringify(j).includes(SECRET_KEY), "diagnostics must not leak the provider key");
});

test("production with no tokens configured refuses protected routes (503), health still works", async () => {
  const s = await start({ env: { NODE_ENV: "production", RAZA_API_TOKENS: "" } });
  try {
    assert.equal((await post(s.base, "/api/v1/assistant", { message: "hi" }, null)).status, 503);
    assert.equal((await fetch(s.base + "/api/v1/health")).status, 200);
  } finally { await stop(s); }
});

test("parseTokens ignores short tokens and bad roles", () => {
  const m = parseTokens("short:user,longenoughtoken123456:admin,longenoughtoken654321:root");
  assert.equal(m.size, 1);
  assert.equal(m.get("longenoughtoken123456"), "admin");
});

// ---------- assistant ----------
test("assistant valid request returns reply", async () => {
  const r = await post(ctx.base, "/api/v1/assistant", { message: "  hello  ", language: "Urdu", history: [{ role: "user", content: "a" }, { role: "assistant", content: "b" }] });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.reply, "echo: hello");
  assert.equal(j.mode, "cloud");
});

test("assistant validation: missing, empty, wrong type, too long, bad language, bad history", async () => {
  const cases = [
    {},
    { message: "" },
    { message: "   " },
    { message: 42 },
    { message: "x".repeat(2001) },
    { message: "hi", language: "Klingon" },
    { message: "hi", history: "nope" },
    { message: "hi", history: [{ role: "system", content: "x" }] },
    { message: "hi", history: Array.from({ length: 21 }, () => ({ role: "user", content: "x" })) },
  ];
  for (const c of cases) {
    const r = await post(ctx.base, "/api/v1/assistant", c);
    assert.equal(r.status, 400, JSON.stringify(c).slice(0, 60));
    assert.equal((await r.json()).error.code, "VALIDATION_ERROR");
  }
});

test("malformed JSON 400, array body 400, wrong content-type 415, oversize 413", async () => {
  assert.equal((await post(ctx.base, "/api/v1/assistant", "{not json")).status, 400);
  assert.equal((await post(ctx.base, "/api/v1/assistant", "[1,2]")).status, 400);
  const r415 = await fetch(ctx.base + "/api/v1/assistant", { method: "POST", headers: { "content-type": "text/plain", authorization: `Bearer ${USER}` }, body: "hi" });
  assert.equal(r415.status, 415);
  const big = await post(ctx.base, "/api/v1/assistant", JSON.stringify({ message: "x".repeat(20000) }));
  assert.equal(big.status, 413);
});

// ---------- provider failures: must be reported honestly ----------
test("provider timeout -> 504, never a fake reply", async () => {
  const s = await start({ provider: { reply: async () => { throw new ProviderError("PROVIDER_TIMEOUT", "AI provider timed out"); } } });
  try {
    const r = await post(s.base, "/api/v1/assistant", { message: "hi" });
    assert.equal(r.status, 504);
    const j = await r.json();
    assert.equal(j.error.code, "PROVIDER_TIMEOUT");
    assert.equal(j.reply, undefined);
  } finally { await stop(s); }
});

test("provider unavailable -> 503; provider error -> 502; unexpected throw -> 500 with no stack", async () => {
  for (const [err, status] of [
    [new ProviderError("PROVIDER_UNAVAILABLE", "not configured"), 503],
    [new ProviderError("PROVIDER_ERROR", "bad"), 502],
    [new Error("boom at /secret/path.js:1"), 500],
  ]) {
    const s = await start({ provider: { reply: async () => { throw err; } } });
    try {
      const r = await post(s.base, "/api/v1/assistant", { message: "hi" });
      assert.equal(r.status, status);
      const text = await r.text();
      assert.ok(!text.includes("/secret/path"), "internal details must not leak");
    } finally { await stop(s); }
  }
});

// ---------- anthropic adapter (real code, injected fetch) ----------
test("adapter: no API key -> PROVIDER_UNAVAILABLE", async () => {
  const p = createAnthropicProvider({ apiKey: "", model: "m", timeoutMs: 100 });
  await assert.rejects(p.reply({ message: "x", language: "English", history: [] }), (e) => e.code === "PROVIDER_UNAVAILABLE");
});

test("adapter: network failure -> PROVIDER_ERROR without leaking the key", async () => {
  const p = createAnthropicProvider({ apiKey: SECRET_KEY, model: "m", timeoutMs: 100, fetchImpl: async () => { throw new TypeError("fetch failed"); } });
  await assert.rejects(p.reply({ message: "x", language: "English", history: [] }), (e) => e.code === "PROVIDER_ERROR" && !e.message.includes(SECRET_KEY));
});

test("adapter: hanging provider is aborted -> PROVIDER_TIMEOUT", async () => {
  const hang = (_u, { signal }) => new Promise((_, rej) => signal.addEventListener("abort", () => rej(Object.assign(new Error("aborted"), { name: "AbortError" }))));
  const p = createAnthropicProvider({ apiKey: SECRET_KEY, model: "m", timeoutMs: 50, fetchImpl: hang });
  const t0 = Date.now();
  await assert.rejects(p.reply({ message: "x", language: "English", history: [] }), (e) => e.code === "PROVIDER_TIMEOUT");
  assert.ok(Date.now() - t0 < 2000);
});

test("adapter: HTTP error and empty reply are errors; success parses text and sends key only as header", async () => {
  const mk = (res) => createAnthropicProvider({ apiKey: SECRET_KEY, model: "m", timeoutMs: 500, fetchImpl: async () => res });
  await assert.rejects(mk({ ok: false, status: 500 }).reply({ message: "x", language: "English", history: [] }), (e) => e.code === "PROVIDER_ERROR");
  await assert.rejects(mk({ ok: true, json: async () => ({ content: [] }) }).reply({ message: "x", language: "English", history: [] }), (e) => e.code === "PROVIDER_ERROR");
  let seen;
  const p = createAnthropicProvider({ apiKey: SECRET_KEY, model: "m", timeoutMs: 500, fetchImpl: async (u, o) => { seen = o; return { ok: true, json: async () => ({ content: [{ type: "text", text: "salam" }] }) }; } });
  assert.equal(await p.reply({ message: "hi", language: "Urdu", history: [] }), "salam");
  assert.equal(seen.headers["x-api-key"], SECRET_KEY);
  assert.ok(!seen.body.includes(SECRET_KEY));
});

// ---------- command plan ----------
const plan = async (message) => (await (await post(ctx.base, "/api/v1/command-plan", { message })).json()).plan;

test("command-plan: call requires confirmation and is never executed by backend", async () => {
  const r = await post(ctx.base, "/api/v1/command-plan", { message: "call +92 300 1234567" });
  const j = await r.json();
  assert.equal(r.status, 200);
  assert.equal(j.executedByBackend, false);
  assert.equal(j.plan.intent, "DIAL");
  assert.equal(j.plan.actions[0].params.number, "+923001234567");
  assert.equal(j.plan.actions[0].requiresConfirmation, true);
});

test("command-plan: invalid phone number yields no action", async () => {
  const p = await plan("call abc; rm -rf");
  assert.equal(p.actions.length, 0);
  assert.ok(p.clarification);
});

test("command-plan: SMS keeps body, is sensitive", async () => {
  const p = await plan("Send SMS to 03001234567 saying I will be late");
  assert.equal(p.intent, "PREPARE_SMS");
  assert.equal(p.actions[0].params.body, "I will be late");
  assert.equal(p.actions[0].requiresConfirmation, true);
});

test("command-plan: alarm parsing incl. pm and invalid time", async () => {
  const a = await plan("set alarm for 7:30 pm");
  assert.deepEqual(a.actions[0].params, { hour: 19, minute: 30 });
  assert.equal(a.actions[0].requiresConfirmation, true);
  const bad = await plan("set alarm for 25:99");
  assert.equal(bad.actions.length, 0);
});

test("command-plan: read-only info needs no confirmation, device-changing does", async () => {
  const t = await plan("what is the time");
  assert.equal(t.actions[0].type, "GET_TIME");
  assert.equal(t.actions[0].requiresConfirmation, false);
  const b = await plan("what is my battery level");
  assert.equal(b.actions[0].type, "GET_BATTERY");
  const v = await plan("volume up");
  assert.equal(v.actions[0].requiresConfirmation, true);
  const w = await plan("open wifi settings");
  assert.equal(w.actions[0].type, "OPEN_SETTINGS");
  assert.equal(w.actions[0].params.screen, "WIFI");
  const app = await plan("open YouTube");
  assert.equal(app.actions[0].params.name, "YouTube");
  assert.equal(app.actions[0].requiresConfirmation, true);
});

test("command-plan: Urdu phrases and unknown text", async () => {
  const w = await plan("وائی فائی کھولو");
  assert.equal(w.actions[0].params.screen, "WIFI");
  const u = await plan("tell me a joke");
  assert.equal(u.intent, "NONE");
  assert.equal(u.actions.length, 0);
});

test("command-plan validation mirrors assistant", async () => {
  assert.equal((await post(ctx.base, "/api/v1/command-plan", {})).status, 400);
});

// ---------- rate limiting, logging, security ----------
test("rate limit returns 429 with Retry-After after the limit", async () => {
  const s = await start({ env: { RATE_LIMIT_PER_MIN: "3" } });
  try {
    const codes = [];
    let last;
    for (let i = 0; i < 5; i++) { last = await post(s.base, "/api/v1/command-plan", { message: "volume up" }); codes.push(last.status); }
    assert.deepEqual(codes, [200, 200, 200, 429, 429]);
    assert.ok(Number(last.headers.get("retry-after")) >= 1);
  } finally { await stop(s); }
});

test("logs never contain request bodies, tokens or keys", async () => {
  const s = await start();
  try {
    await post(s.base, "/api/v1/assistant", { message: "my private secret message" });
    await post(s.base, "/api/v1/assistant", { message: "x" }, "wrong-token-0123456789abcdef");
    const dump = JSON.stringify(s.logs);
    assert.ok(s.logs.length >= 2);
    for (const bad of ["private secret", USER, ADMIN, SECRET_KEY, "wrong-token"]) assert.ok(!dump.includes(bad), `log leaked: ${bad}`);
  } finally { await stop(s); }
});

test("responses carry security headers and no server fingerprint", async () => {
  const r = await fetch(ctx.base + "/api/v1/health");
  assert.equal(r.headers.get("x-content-type-options"), "nosniff");
  assert.equal(r.headers.get("cache-control"), "no-store");
  assert.equal(r.headers.get("x-powered-by"), null);
});
