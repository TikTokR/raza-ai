import { createApp } from "./app.js";
import { createAnthropicProvider } from "./ai.js";

const env = process.env;
const timeoutMs = Number(env.PROVIDER_TIMEOUT_MS || 20000);
const provider = createAnthropicProvider({
  apiKey: env.ANTHROPIC_API_KEY,
  model: env.RAZA_MODEL || "claude-sonnet-5-5",
  timeoutMs,
});

// Request log lines contain method, path, status and timing only. Never bodies, tokens or keys.
const log = (entry) => console.log(JSON.stringify(entry));

const server = createApp({ env, provider, log });
const port = Number(env.PORT || 8080);
server.listen(port, () => console.log(JSON.stringify({ msg: "raza-ai-backend listening", port })));
