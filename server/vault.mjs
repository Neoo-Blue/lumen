// API connections: anything with an HTTP API and a key. The key is encrypted at rest (AES-256-GCM, LUMEN_VAULT_KEY),
// kept per person and profile, and only ever injected into requests to that service's own host. The model never sees it.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createCipheriv, createDecipheriv, randomBytes, createHash, hkdfSync } from 'node:crypto';
import { isPublicHost, safeFetch } from './net.mjs';

import { readFileSync, writeFileSync } from 'node:fs';
import { DATA } from './config.mjs';
// a random key made on first start and kept beside the data (owner-only), or LUMEN_VAULT_KEY; separated by HKDF
const keyFile = `${DATA}/vault.key`;
const master = process.env.LUMEN_VAULT_KEY || (() => { try { return readFileSync(keyFile, 'utf8').trim(); } catch { const k = randomBytes(32).toString('hex'); writeFileSync(keyFile, k, { mode: 0o600 }); return k; } })();
const KEY = Buffer.from(hkdfSync('sha256', master, 'lumen', 'vault-v1', 32));
export const seal = text => { const iv = randomBytes(12), c = createCipheriv('aes-256-gcm', KEY, iv); const enc = Buffer.concat([c.update(text, 'utf8'), c.final()]); return [iv, c.getAuthTag(), enc].map(b => b.toString('base64')).join('.'); };
export const unseal = blob => open(blob);
const open = blob => { const [iv, tag, enc] = blob.split('.').map(b => Buffer.from(b, 'base64')); const d = createDecipheriv('aes-256-gcm', KEY, iv); d.setAuthTag(tag); return Buffer.concat([d.update(enc), d.final()]).toString('utf8'); };

const file = dir => `${dir}/apis.json`;
const id = s => String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 30) || 'api';
async function load(dir) { try { return JSON.parse(await readFile(file(dir), 'utf8')); } catch { return {}; } }
async function store(dir, all) { await mkdir(dir, { recursive: true }); await writeFile(file(dir), JSON.stringify(all, null, 1), { mode: 0o600 }); }

// auth: { type: 'bearer' | 'header' | 'basic' | 'query', name?: header or query parameter name }
export async function add(dir, { service, base_url, auth = { type: 'bearer' }, secret, docs }) {
  const u = new URL(base_url);
  if (u.protocol !== 'https:' || !(await isPublicHost(u.hostname))) throw new Error('only public https APIs');
  if (!secret || String(secret).length > 4000) throw new Error('missing key');
  const all = await load(dir);
  const k = id(service);
  all[k] = { service: String(service).slice(0, 60), base_url: u.origin + u.pathname.replace(/\/$/, ''), auth: { type: ['bearer', 'header', 'basic', 'query'].includes(auth.type) ? auth.type : 'bearer', name: auth.name ? String(auth.name).slice(0, 60) : undefined }, docs, secret: seal(String(secret).trim()), added: new Date().toISOString() };
  await store(dir, all);
  return k;
}
export async function remove(dir, k) { const all = await load(dir); delete all[k]; await store(dir, all); }
export async function list(dir) { return Object.entries(await load(dir)).map(([k, v]) => ({ id: k, service: v.service, base_url: v.base_url, added: v.added })); }

export async function call(dir, { service, method = 'GET', path = '/', query, body, headers }) {
  const all = await load(dir), a = all[id(service)] || Object.values(all).find(x => x.service.toLowerCase() === String(service).toLowerCase());
  if (!a) return { error: `not connected: ${service}. Use connect_api first.` };
  const base = new URL(a.base_url);
  if (/^[a-z][a-z0-9+.-]*:|^\/\//i.test(String(path))) return { error: 'give a path relative to the service, not a full URL' };
  const url = new URL(a.base_url + (String(path).startsWith('/') ? '' : '/') + path);
  const prefix = base.pathname.replace(/\/?$/, '/');
  if (url.origin !== base.origin || url.protocol !== 'https:' || !(url.pathname + '/').startsWith(prefix)) return { error: 'path must stay on the service' };
  for (const [k, v] of Object.entries(query || {})) url.searchParams.set(k, String(v));
  const secret = open(a.secret), h = { accept: 'application/json', 'user-agent': 'Lumen/0.4', ...(headers || {}) };
  if (a.auth.type === 'bearer') h.authorization = `Bearer ${secret}`;
  if (a.auth.type === 'header') h[a.auth.name || 'x-api-key'] = secret;
  if (a.auth.type === 'basic') h.authorization = `Basic ${Buffer.from(secret.includes(':') ? secret : secret + ':').toString('base64')}`;
  if (a.auth.type === 'query') url.searchParams.set(a.auth.name || 'api_key', secret);
  if (body !== undefined && !h['content-type']) h['content-type'] = 'application/json';
  const r = await safeFetch(url, { method: String(method).toUpperCase(), headers: h, body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body), signal: AbortSignal.timeout(20000) });
  let text = await r.text();
  // never echo the key back to the model, in any encoding
  for (const form of [secret, encodeURIComponent(secret), Buffer.from(secret).toString('base64'), Buffer.from(secret.includes(':') ? secret : secret + ':').toString('base64')]) text = text.split(form).join('[key]');
  return { status: r.status, body: text.slice(0, 12000) };
}

// node vault.mjs -> self-check (no network beyond DNS)
if (import.meta.url === `file://${process.argv[1]}`) {
  const { strict: assert } = await import('node:assert');
  const dir = '/tmp/lumen-vaulttest';
  const k = await add(dir, { service: 'Tailscale', base_url: 'https://api.tailscale.com/api/v2', secret: 'tskey-api-abc' });
  assert.equal(k, 'tailscale');
  const raw = await readFile(file(dir), 'utf8'); assert.ok(!raw.includes('tskey-api-abc'), 'key must be encrypted at rest');
  assert.equal((await list(dir))[0].service, 'Tailscale');
  assert.match((await call(dir, { service: 'tailscale', path: 'https://evil.example.com/x' })).error, /relative/);
  assert.match((await call(dir, { service: 'tailscale', path: '//evil.example.com/x' })).error, /relative/);
  assert.match((await call(dir, { service: 'tailscale', path: '/../../v3/x' })).error, /stay/);
  await assert.rejects(add(dir, { service: 'x', base_url: 'https://192.168.1.1/', secret: 'k' }));
  await remove(dir, 'tailscale'); assert.equal((await list(dir)).length, 0);
  console.log('vault ok');
}
