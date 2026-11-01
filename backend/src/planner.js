// Deterministic command planner (English + Urdu keywords).
// The backend only PLANS. The Android app must confirm and execute every action.
// Any action that changes the device or communicates externally is always
// marked sensitive + requiresConfirmation here, regardless of the request.

const PHONE_RE = /^\+?[0-9][0-9\s\-()]{5,18}[0-9]$/;

export function normalizePhone(raw) {
  if (typeof raw !== "string") return null;
  const t = raw.trim();
  if (!PHONE_RE.test(t)) return null;
  return t.replace(/[\s\-()]/g, "");
}

const SETTINGS = [
  { keys: ["wifi", "wi-fi", "وائی فائی"], screen: "WIFI" },
  { keys: ["bluetooth", "بلوٹوتھ"], screen: "BLUETOOTH" },
  { keys: ["battery", "بیٹری سیور"], screen: "BATTERY" },
  { keys: ["settings", "setting", "سیٹنگ"], screen: "MAIN" },
];

function action(type, params, summary, sensitive) {
  return { type, params, summary, sensitive, requiresConfirmation: sensitive };
}

export function planCommand(message) {
  const text = String(message).trim();
  const q = text.toLowerCase();

  // Phone call
  let m = q.match(/^(?:call|dial|phone)\s+(.+)$/) || q.match(/^(.+?)\s+(?:کو\s+)?کال\s+کرو$/);
  if (m) {
    const number = normalizePhone(m[1]);
    if (!number) {
      return { intent: "DIAL", confidence: 0.4, actions: [], clarification: "Please give a valid phone number." };
    }
    return {
      intent: "DIAL",
      confidence: 0.9,
      actions: [action("DIAL", { number }, `Open the dialer for ${number}`, true)],
    };
  }

  // SMS: "send sms to <number> saying <text>" / "text <number> <text>"
  m = text.match(/^(?:send\s+(?:an?\s+)?(?:sms|message|text)\s+to|text|sms)\s+(\+?[0-9][0-9\s\-()]{5,18}[0-9])\s*(?:saying|:|that)?\s*(.*)$/i);
  if (m) {
    const number = normalizePhone(m[1]);
    const body = (m[2] || "").trim();
    if (!number) {
      return { intent: "PREPARE_SMS", confidence: 0.4, actions: [], clarification: "Please give a valid phone number." };
    }
    return {
      intent: "PREPARE_SMS",
      confidence: 0.85,
      actions: [action("PREPARE_SMS", { number, body: body.slice(0, 500) }, `Prepare an SMS to ${number} (you press send)`, true)],
    };
  }

  // Alarm: "set alarm for 7:30" / "wake me at 6"
  m = q.match(/(?:set\s+(?:an\s+)?alarm(?:\s+for|\s+at)?|wake me(?:\s+up)?\s+at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?/);
  if (m) {
    let hour = parseInt(m[1], 10);
    const minute = m[2] ? parseInt(m[2], 10) : 0;
    if (m[3] === "pm" && hour < 12) hour += 12;
    if (m[3] === "am" && hour === 12) hour = 0;
    if (hour > 23 || minute > 59) {
      return { intent: "SET_ALARM", confidence: 0.3, actions: [], clarification: "That time is not valid." };
    }
    const hh = String(hour).padStart(2, "0");
    const mm = String(minute).padStart(2, "0");
    return {
      intent: "SET_ALARM",
      confidence: 0.85,
      actions: [action("SET_ALARM", { hour, minute }, `Create an alarm for ${hh}:${mm}`, true)],
    };
  }

  // Volume
  m = q.match(/\bvolume\s+(up|down)\b|\b(?:increase|decrease|raise|lower)\s+(?:the\s+)?volume\b/);
  if (m) {
    const up = m[1] === "up" || /increase|raise/.test(m[0]);
    return { intent: "VOLUME", confidence: 0.8, actions: [action("VOLUME", { direction: up ? "UP" : "DOWN" }, `Turn volume ${up ? "up" : "down"}`, true)] };
  }

  // Media
  m = q.match(/\b(play|pause|resume|next track|next song|previous track|stop music)\b/);
  if (m && /music|song|track|media|play$|pause$|resume$/.test(q)) {
    const map = { play: "PLAY", resume: "PLAY", pause: "PAUSE", "stop music": "PAUSE", "next track": "NEXT", "next song": "NEXT", "previous track": "PREVIOUS" };
    return { intent: "MEDIA", confidence: 0.7, actions: [action("MEDIA", { command: map[m[1]] }, `Media: ${map[m[1]].toLowerCase()}`, true)] };
  }

  // Read-only information
  if (/\b(what(?:'s| is) the time|current time|time now)\b|وقت کیا/.test(q)) {
    return { intent: "GET_TIME", confidence: 0.9, actions: [action("GET_TIME", {}, "Read the current time", false)] };
  }
  if (/\b(what(?:'s| is) the date|today'?s date|current date)\b|تاریخ/.test(q)) {
    return { intent: "GET_DATE", confidence: 0.9, actions: [action("GET_DATE", {}, "Read the current date", false)] };
  }
  if (/\bbattery\b(?!.*settings)|بیٹری/.test(q) && !/settings|سیٹنگ/.test(q)) {
    return { intent: "GET_BATTERY", confidence: 0.85, actions: [action("GET_BATTERY", {}, "Read the battery level", false)] };
  }

  // Settings screens
  if (/^(?:open|show|go to)\b.*\b(settings?|wifi|wi-fi|bluetooth)\b|کھولو|کھول/.test(q)) {
    for (const s of SETTINGS) {
      if (s.keys.some((k) => q.includes(k))) {
        return { intent: "OPEN_SETTINGS", confidence: 0.85, actions: [action("OPEN_SETTINGS", { screen: s.screen }, `Open ${s.screen.toLowerCase()} settings`, true)] };
      }
    }
  }

  // Open app
  m = q.match(/^(?:open|launch|start)\s+(.{1,60})$/);
  if (m) {
    const app = text.slice(text.length - m[1].length).trim();
    return { intent: "OPEN_APP", confidence: 0.7, actions: [action("OPEN_APP", { name: app }, `Open the app "${app}"`, true)] };
  }

  return { intent: "NONE", confidence: 0.2, actions: [] };
}
