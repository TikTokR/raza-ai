// Real-network check of a deployed backend, exactly as the Android app uses it.
//   RAZA_TOKEN=<phone token> node scripts/verify-deploy.mjs https://your-host
const base = (process.argv[2] || "").replace(/\/+$/, "").replace(/\/api\/v1$/, "");
const token = process.env.RAZA_TOKEN || "";
const localTest = process.env.RAZA_ALLOW_HTTP_LOCAL === "1" && /^http:\/\/127\.0\.0\.1(:\d+)?$/.test(base); // self-test only
if (!localTest && !/^https:\/\/[^/\s@]+\.[^/\s@]+$|^https:\/\/localhost(:\d+)?$/.test(base) || token.length < 16) {
  console.error("Usage: RAZA_TOKEN=<token, 16+ chars> node scripts/verify-deploy.mjs https://host");
  process.exit(2);
}
let failed = 0;
const call = async (method, path, tok, body) => {
  let r;
  try { r = await fetch(base + path, {
    method,
    headers: { ...(tok ? { authorization: `Bearer ${tok}` } : {}), ...(body ? { "content-type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30000),
  }); } catch (e) { return { status: 0, json: null, net: String(e.cause?.code || e.message) }; }
  let j = null; try { j = await r.json(); } catch {}
  return { status: r.status, json: j };
};
const check = (name, ok, detail = "") => { console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  (" + detail + ")" : ""}`); if (!ok) failed++; };

const h = await call("GET", "/api/v1/health");
check("health is reachable over HTTPS", h.status === 200 && h.json?.status === "ok", `HTTP ${h.status}`);
const none = await call("POST", "/api/v1/command-plan", null, { message: "volume up" });
check("request without a token is rejected (401)", none.status === 401, `HTTP ${none.status}`);
const plan = await call("POST", "/api/v1/command-plan", token, { message: "volume up", language: "English" });
check("your token is accepted by /command-plan", plan.status === 200 && plan.json?.plan?.actions?.[0]?.type === "VOLUME", `HTTP ${plan.status}`);
const diag = await call("GET", "/api/v1/diagnostics", token);
check("your token is role=user, NOT admin (diagnostics must be 403)", diag.status === 403, `HTTP ${diag.status}`);
const ai = await call("POST", "/api/v1/assistant", token, { message: "Reply with the single word: pong", language: "English", history: [] });
if (ai.status === 200 && typeof ai.json?.reply === "string") check("text chat: /assistant returned a real AI reply", true, `reply: ${ai.json.reply.slice(0, 60)}`);
else check("text chat: /assistant returned a real AI reply", false, `HTTP ${ai.status} ${ai.json?.error?.code || ""} - ${ai.status === 503 ? "set ANTHROPIC_API_KEY on the server" : "see server logs"}`);
console.log(failed ? `\n${failed} check(s) FAILED` : "\nAll checks passed");
process.exit(failed ? 1 : 0);
