// AI provider adapter. The provider key lives ONLY in the server environment.
// If no key is configured, callers get an explicit "unavailable" error;
// the backend never fabricates a cloud answer.

export class ProviderError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code; // "PROVIDER_UNAVAILABLE" | "PROVIDER_TIMEOUT" | "PROVIDER_ERROR"
  }
}

const SYSTEM_PROMPT =
  "You are RAZA AI, a concise, helpful personal voice assistant. " +
  "Reply in the same language as the user (English or Urdu). Keep replies short enough to be spoken aloud. " +
  "You cannot perform phone actions yourself; the app asks the user to confirm them. " +
  "Never ask for passwords or PINs.";

export function createAnthropicProvider({ apiKey, model, timeoutMs, fetchImpl = fetch }) {
  return {
    async reply({ message, language, history }) {
      if (!apiKey) throw new ProviderError("PROVIDER_UNAVAILABLE", "AI provider is not configured");
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), timeoutMs);
      try {
        const res = await fetchImpl("https://api.anthropic.com/v1/messages", {
          method: "POST",
          signal: ctrl.signal,
          headers: {
            "content-type": "application/json",
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
          },
          body: JSON.stringify({
            model,
            max_tokens: 400,
            system: `${SYSTEM_PROMPT} Preferred language: ${language}.`,
            messages: [...history, { role: "user", content: message }],
          }),
        });
        if (!res.ok) throw new ProviderError("PROVIDER_ERROR", `Provider returned HTTP ${res.status}`);
        const data = await res.json();
        const text = (data.content || []).filter((b) => b.type === "text").map((b) => b.text).join("").trim();
        if (!text) throw new ProviderError("PROVIDER_ERROR", "Provider returned an empty reply");
        return text;
      } catch (e) {
        if (e instanceof ProviderError) throw e;
        if (e && e.name === "AbortError") throw new ProviderError("PROVIDER_TIMEOUT", "AI provider timed out");
        throw new ProviderError("PROVIDER_ERROR", "AI provider network failure");
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

// OpenAI Chat Completions adapter (same contract as the Anthropic one).
export function createOpenAIProvider({ apiKey, model, timeoutMs, fetchImpl = fetch }) {
  return {
    async reply({ message, language, history }) {
      if (!apiKey) throw new ProviderError("PROVIDER_UNAVAILABLE", "AI provider is not configured");
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), timeoutMs);
      try {
        const res = await fetchImpl("https://api.openai.com/v1/chat/completions", {
          method: "POST",
          signal: ctrl.signal,
          headers: { "content-type": "application/json", authorization: `Bearer ${apiKey}` },
          body: JSON.stringify({
            model,
            max_completion_tokens: 400,
            messages: [
              { role: "system", content: `${SYSTEM_PROMPT} Preferred language: ${language}.` },
              ...history,
              { role: "user", content: message },
            ],
          }),
        });
        if (!res.ok) throw new ProviderError("PROVIDER_ERROR", `Provider returned HTTP ${res.status}`);
        const data = await res.json();
        const text = String(data?.choices?.[0]?.message?.content ?? "").trim();
        if (!text) throw new ProviderError("PROVIDER_ERROR", "Provider returned an empty reply");
        return text;
      } catch (e) {
        if (e instanceof ProviderError) throw e;
        if (e && e.name === "AbortError") throw new ProviderError("PROVIDER_TIMEOUT", "AI provider timed out");
        throw new ProviderError("PROVIDER_ERROR", "AI provider network failure");
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

/** Pick the provider from the environment. AI_PROVIDER=openai|anthropic; otherwise whichever key is set (Anthropic wins if both). */
export function createProviderFromEnv(env, fetchImpl = fetch) {
  const timeoutMs = Number(env.PROVIDER_TIMEOUT_MS || 20000);
  const which = (env.AI_PROVIDER || (env.ANTHROPIC_API_KEY ? "anthropic" : env.OPENAI_API_KEY ? "openai" : "anthropic")).toLowerCase();
  if (which === "openai") {
    return createOpenAIProvider({ apiKey: env.OPENAI_API_KEY, model: env.OPENAI_MODEL || "gpt-4o-mini", timeoutMs, fetchImpl });
  }
  return createAnthropicProvider({ apiKey: env.ANTHROPIC_API_KEY, model: env.RAZA_MODEL || "claude-sonnet-5-5", timeoutMs, fetchImpl });
}
