// The person's mailbox, read by a script: new mail is checked every few minutes over IMAP, each message goes through
// mailfacts.mjs (no AI), and only the facts (payments, deliveries, bookings, bills...) and a one-line index are kept.
// The AI reads that digest, searches the index, and opens a single message only when it needs the words.
import { ImapFlow } from 'imapflow';
import { simpleParser } from 'mailparser';
import { extract, digestLine } from './mailfacts.mjs';

const HOSTS = { 'gmail.com': 'imap.gmail.com', 'googlemail.com': 'imap.gmail.com', 'icloud.com': 'imap.mail.me.com', 'me.com': 'imap.mail.me.com', 'mac.com': 'imap.mail.me.com',
  'yahoo.com': 'imap.mail.yahoo.com', 'aol.com': 'imap.aol.com', 'fastmail.com': 'imap.fastmail.com', 'zoho.com': 'imap.zoho.com', 'gmx.com': 'imap.gmx.com',
  'qq.com': 'imap.qq.com', 'foxmail.com': 'imap.qq.com', '163.com': 'imap.163.com', '126.com': 'imap.126.com', 'yeah.net': 'imap.yeah.net', 'sina.com': 'imap.sina.com' };
export const hostFor = addr => { const d = String(addr).split('@')[1]?.toLowerCase() || ''; return HOSTS[d] || (d ? 'imap.' + d : ''); };

async function open({ addr, host, pass, token }) {
  const c = new ImapFlow({ host: host || hostFor(addr), port: 993, secure: true, auth: token ? { user: addr, accessToken: token } : { user: addr, pass }, logger: false, socketTimeout: 60000, connectionTimeout: 20000 });
  c.on('error', () => {}); // a dropped connection must not take the server down; the next check reconnects
  await c.connect();
  return c;
}

// one-tap sign-in (OAuth with PKCE) for Outlook and Gmail; on when their client ids are set on the server
export const OAUTH = {
  ms: { id: process.env.LUMEN_MS_CLIENT_ID, auth: 'https://login.microsoftonline.com/common/oauth2/v2.0/authorize', token: 'https://login.microsoftonline.com/common/oauth2/v2.0/token', scope: 'openid email offline_access https://outlook.office.com/IMAP.AccessAsUser.All', host: 'outlook.office365.com' },
  google: { id: process.env.LUMEN_GOOGLE_CLIENT_ID, secret: process.env.LUMEN_GOOGLE_CLIENT_SECRET, auth: 'https://accounts.google.com/o/oauth2/v2/auth', token: 'https://oauth2.googleapis.com/token', scope: 'openid email https://mail.google.com/', host: 'imap.gmail.com', extra: { access_type: 'offline', prompt: 'consent' } },
};
export const oauthReady = () => Object.fromEntries(Object.entries(OAUTH).map(([k, v]) => [k, !!v.id && (k !== 'google' || !!v.secret)]));
export function authUrl(provider, { state, challenge, redirect }) {
  const o = OAUTH[provider]; return `${o.auth}?${new URLSearchParams({ client_id: o.id, response_type: 'code', redirect_uri: redirect, scope: o.scope, state, code_challenge: challenge, code_challenge_method: 'S256', ...(o.extra || {}) })}`;
}
async function tokenCall(provider, form) {
  const o = OAUTH[provider], r = await fetch(o.token, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ client_id: o.id, ...(o.secret ? { client_secret: o.secret } : {}), ...form }), signal: AbortSignal.timeout(15000) });
  const j = await r.json().catch(() => ({})); if (!r.ok || !j.access_token) throw new Error(j.error_description || j.error || 'sign-in failed');
  let email = ''; try { email = JSON.parse(Buffer.from(String(j.id_token).split('.')[1], 'base64url').toString()).email || ''; } catch {}
  return { access: j.access_token, refresh: j.refresh_token, exp: Date.now() + (j.expires_in || 3600) * 1000, email };
}
export const exchange = (provider, code, verifier, redirect) => tokenCall(provider, { grant_type: 'authorization_code', code, code_verifier: verifier, redirect_uri: redirect });
export const refresh = (provider, token) => tokenCall(provider, { grant_type: 'refresh_token', refresh_token: token, scope: OAUTH[provider].scope });

/** Signs in once to check the address and password (an app password for most providers). */
export async function test(cfg) { const c = await open(cfg); await c.logout().catch(() => {}); return true; }

const clip = (s, n) => String(s || '').replace(/\s+/g, ' ').trim().slice(0, n);

/**
 * New mail since the last check: facts and index lines, never bodies. The first check looks back a week.
 * seen = { uid, validity } from the last check; returns the new one with the results.
 */
export async function poll(cfg, seen = {}) {
  const c = await open(cfg);
  const facts = [], index = [];
  let uid = seen.uid || 0, validity = seen.validity;
  try {
    const lock = await c.getMailboxLock('INBOX');
    try {
      const v = String(c.mailbox.uidValidity);
      if (validity && v !== validity) uid = 0; // the mailbox was rebuilt: its numbers mean something else now
      validity = v;
      let uids = uid ? await c.search({ uid: `${uid + 1}:*` }, { uid: true }) : await c.search({ since: new Date(Date.now() - 7 * 864e5) }, { uid: true });
      uids = (uids || []).filter(u => u > uid).sort((a, b) => a - b).slice(-150); // a big backlog: the newest 150
      for (const u of uids) {
        const m = await c.fetchOne(String(u), { uid: true, size: true, envelope: true }, { uid: true });
        if (!m) continue;
        uid = Math.max(uid, u);
        const env = m.envelope || {};
        const from = env.from?.[0] ? `${env.from[0].name || ''} <${env.from[0].address || ''}>`.trim() : '';
        let text = '', html = '', attachments = [];
        if ((m.size || 0) < 3e6) {
          const full = await c.fetchOne(String(u), { source: true }, { uid: true });
          const p = await simpleParser(full.source, { skipImageLinks: true });
          text = String(p.text || '').slice(0, 30000); html = typeof p.html === 'string' ? p.html.slice(0, 300000) : '';
          attachments = (p.attachments || []).filter(a => /calendar/i.test(a.contentType) && a.size < 2e5).map(a => ({ contentType: a.contentType, filename: a.filename, content: a.content.toString('utf8') }));
        }
        const date = (env.date ? new Date(env.date) : new Date()).toISOString();
        index.push({ uid: u, date, from: clip(from, 120), subject: clip(env.subject, 200), snippet: clip(text || html.replace(/<[^>]+>/g, ' '), 220) });
        try { for (const f of extract({ from, to: '', subject: env.subject || '', date, text, html, attachments })) facts.push({ ...f, uid: u, got: date, line: digestLine(f) }); } catch {}
      }
    } finally { lock.release(); }
  } finally { await c.logout().catch(() => {}); }
  return { facts, index, seen: { uid, validity } };
}

/** One message's words, for when the AI needs them (trimmed; links and images dropped). */
export async function read(cfg, uid) {
  const c = await open(cfg);
  try {
    const lock = await c.getMailboxLock('INBOX');
    try {
      const full = await c.fetchOne(String(uid), { source: true }, { uid: true });
      if (!full) return null;
      const p = await simpleParser(full.source, { skipImageLinks: true });
      return { from: clip(p.from?.text, 160), subject: clip(p.subject, 200), date: p.date?.toISOString(), text: String(p.text || '').replace(/\n{3,}/g, '\n\n').slice(0, 6000) };
    } finally { lock.release(); }
  } finally { await c.logout().catch(() => {}); }
}

/** Searches the kept index by words (sender, subject, first lines); newest first. */
export function search(index, query, limit = 12) {
  const words = String(query || '').toLowerCase().split(/\s+/).filter(w => w.length > 1);
  const hit = r => { const t = `${r.from} ${r.subject} ${r.snippet}`.toLowerCase(); return words.every(w => t.includes(w)); };
  return (words.length ? index.filter(hit) : index).slice(-limit).reverse();
}

/** Facts merged by reference (an order, then shipped, then delivered stays one line, the latest state). */
export function merge(digest, facts, keep = 400) {
  const out = [...digest];
  for (const f of facts) {
    if (f.uid && out.some(d => d.uid === f.uid && d.kind === f.kind)) continue; // the same message seen twice
    const k = f.ref ? `${f.who || ''}|${f.ref}` : null;
    const at = k ? out.findIndex(d => d.ref && `${d.who || ''}|${d.ref}` === k) : -1;
    if (at >= 0) out[at] = { ...out[at], ...f, history: [...(out[at].history || [out[at].kind]), f.kind].slice(-6) };
    else out.push(f);
  }
  return out.slice(-keep);
}

/** Does a new message match something the person is waiting for? Words all present, and the sender if given. */
export function watchHits(watches, row) {
  const t = `${row.from} ${row.subject} ${row.snippet}`.toLowerCase();
  return (watches || []).filter(w => (!w.from || row.from.toLowerCase().includes(w.from.toLowerCase())) && (w.words || []).every(x => t.includes(String(x).toLowerCase())));
}

if (globalThis.process?.argv?.[1]?.endsWith('mail.mjs')) {
  const assert = await import('node:assert/strict');
  assert.equal(hostFor('a@gmail.com'), 'imap.gmail.com'); assert.equal(hostFor('a@example.org'), 'imap.example.org');
  const d = merge([], [{ kind: 'order', who: 'Shop', ref: 'A1', line: 'order' }]);
  const d2 = merge(d, [{ kind: 'delivered', who: 'Shop', ref: 'A1', line: 'delivered' }]);
  assert.equal(d2.length, 1); assert.equal(d2[0].kind, 'delivered'); assert.deepEqual(d2[0].history, ['order', 'delivered']);
  const row = { from: 'Passport Agency <no-reply@state.example>', subject: 'Your passport has shipped', snippet: '' };
  assert.equal(watchHits([{ words: ['passport'] }], row).length, 1);
  assert.equal(watchHits([{ words: ['passport'], from: 'dmv' }], row).length, 0);
  assert.equal(search([row, { from: 'x', subject: 'lunch', snippet: '' }], 'passport').length, 1);
  console.log('mail ok');
}
