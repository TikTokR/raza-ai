// Generates fresh random tokens locally. Prints them once; nothing is stored or sent anywhere.
//   node scripts/make-token.mjs
import { randomBytes } from "node:crypto";
const user = randomBytes(24).toString("hex");   // goes into the phone
const admin = randomBytes(24).toString("hex");  // stays on the server only
console.log("PHONE token (paste into the app, role=user):\n  " + user);
console.log("\nServer environment variable RAZA_API_TOKENS (paste into your host's settings, never into the app):\n  " + `${user}:user,${admin}:admin`);
console.log("\nKeep the admin token private. The phone never needs it.");
