/**
 * EMPIRE STATS — the admin dashboard's data source. (Deno.serve runtime)
 *
 * Serves aggregate fleet metrics (installs, active users, update downloads,
 * crash reports, Empire Talk network stats) from the Empire's Supabase
 * tables. Supabase keys never leave the server; callers authenticate with
 * the owner's admin key, compared as a SHA-256 hash.
 *
 * Mirror of this file lives in the neverhide-empire repo at
 * admin-dashboard/empireStats.function.js (GitHub is source of truth).
 */
const SB_URL = "https://hokqlvkowcrujppeliip.supabase.co/rest/v1";
const SB_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhva3Fsdmtvd2NydWpwcGVsaWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3MTg1MDUsImV4cCI6MjA5NTI5NDUwNX0._iO6p71kJRiBWH-fJ1j7GWDNmMcjSMN5nseNU4VN8tE";
const EMPIRE_APP_KEY = "empire-talk-647a00bd4a571d2991bf591a4f18f101";
// sha256("empire-46226de048507c5e") — owner's dashboard key
const ADMIN_KEY_SHA256 = "c40e7a3ed161c34f8039b6cf6a148af5635c4bd3404135a1189bac9d36113e41";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Content-Type": "application/json",
};

async function sha256(s) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function sbGet(path) {
  const r = await fetch(SB_URL + path, {
    headers: {
      apikey: SB_ANON,
      Authorization: "Bearer " + SB_ANON,
      "x-supabase-key": EMPIRE_APP_KEY,
    },
  });
  if (!r.ok) throw new Error("supabase " + r.status);
  return r.json();
}

function dayKey(iso) {
  return (iso || "").slice(0, 10);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  try {
    let body = {};
    try { body = await req.json(); } catch (e) {}
    const adminKey = body.admin_key || "";
    if (!adminKey || (await sha256(String(adminKey))) !== ADMIN_KEY_SHA256) {
      return new Response(JSON.stringify({ ok: false, error: "invalid admin key" }), { status: 401, headers: CORS });
    }

    const [events, crashes, profiles, calls, messages] = await Promise.all([
      sbGet("/empire_events?select=device_id,event,version,device,created_at&order=created_at.desc&limit=3000").catch(() => []),
      sbGet("/crash_reports?select=version,device,report,created_at&order=id.desc&limit=10").catch(() => []),
      sbGet("/talk_profiles?select=handle").catch(() => []),
      sbGet("/talk_calls?select=caller,callee,state,created_at&order=created_at.desc&limit=50").catch(() => []),
      sbGet("/talk_messages?select=id&limit=1000").catch(() => []),
    ]);

    const now = Date.now();
    const DAY = 86400000;
    const devices = {}; // device_id -> { lastVersion, lastSeen }
    const daily = {}; // "YYYY-MM-DD" -> { opens, checks, downloads }
    let totalChecks = 0, totalDownloads = 0, totalOpens = 0;

    for (const e of events) {
      const t = Date.parse(e.created_at);
      const d = devices[e.device_id] || (devices[e.device_id] = { lastVersion: "?", lastSeen: 0 });
      if (t > d.lastSeen) { d.lastSeen = t; d.lastVersion = e.version; }
      const k = dayKey(e.created_at);
      const day = daily[k] || (daily[k] = { opens: 0, checks: 0, downloads: 0 });
      if (e.event === "app_open") { day.opens++; totalOpens++; }
      if (e.event === "update_check") { day.checks++; totalChecks++; }
      if (e.event === "update_download") { day.downloads++; totalDownloads++; }
    }

    const deviceIds = Object.keys(devices);
    const active = (ms) => deviceIds.filter((id) => now - devices[id].lastSeen <= ms).length;
    const today = dayKey(new Date().toISOString());

    const versions = {};
    for (const id of deviceIds) {
      const v = devices[id].lastVersion;
      versions[v] = (versions[v] || 0) + 1;
    }

    // last 14 days, oldest first
    const dailySeries = [];
    for (let i = 13; i >= 0; i--) {
      const k = dayKey(new Date(now - i * DAY).toISOString());
      const day = daily[k] || { opens: 0, checks: 0, downloads: 0 };
      dailySeries.push({ date: k, opens: day.opens, checks: day.checks, downloads: day.downloads });
    }

    return new Response(JSON.stringify({
      ok: true,
      fleet: {
        installs: deviceIds.length,
        active_24h: active(DAY),
        active_7d: active(7 * DAY),
        opens_today: (daily[today] || {}).opens || 0,
        total_opens: totalOpens,
        total_update_checks: totalChecks,
        total_update_downloads: totalDownloads,
      },
      versions,
      daily: dailySeries,
      crashes: crashes.map((c) => ({
        version: c.version, device: c.device,
        time: c.created_at, report: (c.report || "").slice(0, 500),
      })),
      talk: {
        profiles: profiles.map((p) => p.handle),
        calls_total: calls.length,
        recent_calls: calls.slice(0, 10).map((c) => ({
          from: c.caller, to: c.callee, state: c.state, time: c.created_at,
        })),
        messages_total: Array.isArray(messages) ? messages.length : 0,
      },
      generated_at: new Date().toISOString(),
    }), { status: 200, headers: CORS });
  } catch (e) {
    return new Response(JSON.stringify({ ok: false, error: String(e && e.message ? e.message : e) }), { status: 500, headers: CORS });
  }
});
