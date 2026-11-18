// One-time move of this browser's saved state from the project's previous name.
//
// ImPrahari VMS was published under another name before, and the web app kept
// its login, layouts and view choices in localStorage under that name's
// prefix ("<name>_access_token", ...). Imported first in main.tsx, so it runs
// before anything reads them: an existing browser stays logged in and keeps
// its layouts. The keys are recognised by what follows the prefix - the names
// this app uses - so the previous name isn't needed here.

const PREFIX = "imprahari_";
// What this app stores after its prefix (exact names, or the start of per-kiosk ones).
const OWN = ["access_token", "refresh_token", "layout", "live_order", "rec_view", "timezone"];
const OWN_STARTS = ["kiosk_layout_", "kiosk_order_"];

try {
  const moves: [string, string][] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    const m = key ? /^([a-z0-9]+)_(.+)$/.exec(key) : null;
    if (!key || !m || key.startsWith(PREFIX)) continue;
    const rest = m[2];
    if (OWN.includes(rest) || OWN_STARTS.some((s) => rest.startsWith(s))) moves.push([key, PREFIX + rest]);
  }
  for (const [key, renamed] of moves) {
    const value = localStorage.getItem(key);
    if (value !== null && localStorage.getItem(renamed) === null) localStorage.setItem(renamed, value);
    localStorage.removeItem(key);
  }
} catch {
  // Storage blocked (private window, site data off): nothing to move.
}

export {};
