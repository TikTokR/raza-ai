import { createApp } from "./app.js";
import { createProviderFromEnv } from "./ai.js";

const env = process.env;
const provider = createProviderFromEnv(env);

// Request log lines contain method, path, status and timing only. Never bodies, tokens or keys.
const log = (entry) => console.log(JSON.stringify(entry));

const server = createApp({ env, provider, log });
const port = Number(env.PORT || 8080);
server.listen(port, () => console.log(JSON.stringify({ msg: "raza-ai-backend listening", port })));
