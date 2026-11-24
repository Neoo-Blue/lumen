// Outbound requests to addresses Lumen does not control (any public API, MCP servers, connected services) go
// through safeFetch: the address is checked at connect time, on the exact IP the socket uses (no DNS rebinding),
// loopback / private / link-local / internal IPv6 ranges are refused, and redirects are never followed.
import http from 'node:http';
import https from 'node:https';
import dns from 'node:dns';
import net from 'node:net';
import { Readable } from 'node:stream';

export function isPrivateIp(ip) {
  if (net.isIPv4(ip)) {
    const [a, b] = ip.split('.').map(Number);
    return a === 0 || a === 10 || a === 127 || (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) || (a === 192 && b === 0) || (a === 198 && (b === 18 || b === 19)) || a >= 224;
  }
  const v = ip.toLowerCase();
  if (v === '::' || v === '::1') return true;
  if (v.startsWith('::ffff:')) return isPrivateIp(v.slice(7));
  return /^(f[c-d]|fe[89ab]|ff|64:ff9b:|100::|2001:db8|2001:0?:)/.test(v) || !v.includes(':');
}

// a dns.lookup replacement that refuses private answers; used by the socket itself
function guardedLookup(host, opts, cb) {
  dns.lookup(host, { ...opts, all: true }, (err, addrs) => {
    if (err) return cb(err);
    if (!addrs.length || addrs.some(a => isPrivateIp(a.address))) return cb(Object.assign(new Error('private address refused'), { code: 'EPRIVATE' }));
    // prefer IPv4: many boxes (the LXC included) have no IPv6 route, and a single answer gets no fallback
    const ordered = [...addrs.filter(a => a.family === 4), ...addrs.filter(a => a.family !== 4)];
    opts.all ? cb(null, ordered) : cb(null, ordered[0].address, ordered[0].family);
  });
}

export async function isPublicHost(hostname) {
  if (net.isIP(hostname)) return !isPrivateIp(hostname);
  const addrs = await dns.promises.lookup(hostname, { all: true }).catch(() => []);
  return addrs.length > 0 && !addrs.some(a => isPrivateIp(a.address));
}

// fetch-compatible (enough for the MCP SDK, api_get and vault calls); https only unless allowHttp
export function safeFetch(input, init = {}, { allowHttp = false } = {}) {
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(typeof input === 'string' ? input : input.url ?? String(input)); } catch (e) { return reject(e); }
    if (!(u.protocol === 'https:' || (allowHttp && u.protocol === 'http:'))) return reject(new Error('only https is allowed'));
    if (net.isIP(u.hostname.replace(/^\[|\]$/g, '')) && isPrivateIp(u.hostname.replace(/^\[|\]$/g, ''))) return reject(new Error('private address refused'));
    const headers = {};
    new Headers(init.headers || (typeof input === 'object' && input.headers) || {}).forEach((v, k) => { headers[k] = v; });
    const mod = u.protocol === 'https:' ? https : http;
    const req = mod.request(u, { method: init.method || 'GET', headers, lookup: guardedLookup, signal: init.signal, timeout: 30000 }, res => {
      const h = new Headers();
      for (const [k, v] of Object.entries(res.headers)) (Array.isArray(v) ? v : [v]).forEach(x => x != null && h.append(k, String(x)));
      const status = res.statusCode || 500, empty = [101, 204, 205, 304].includes(status);
      resolve(new Response(empty ? null : Readable.toWeb(res), { status, statusText: res.statusMessage, headers: h }));
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('timed out')));
    const body = init.body;
    if (body == null) return req.end();
    if (typeof body === 'string' || body instanceof Uint8Array) return req.end(body);
    if (body instanceof URLSearchParams) return req.end(body.toString());
    req.end(String(body));
  });
}

// node net.mjs -> self-check
if (import.meta.url === `file://${process.argv[1]}`) {
  const { strict: assert } = await import('node:assert');
  for (const ip of ['127.0.0.1', '10.1.2.3', '192.168.1.1', '172.20.0.1', '100.100.1.1', '169.254.1.1', '0.0.0.0', '::', '::1', '::ffff:127.0.0.1', 'fd00::1', 'fe80::1', '64:ff9b::7f00:1']) assert.ok(isPrivateIp(ip), ip);
  for (const ip of ['1.1.1.1', '8.8.8.8', '2606:4700:4700::1111']) assert.ok(!isPrivateIp(ip), ip);
  await assert.rejects(safeFetch('http://example.com'), /https/);
  await assert.rejects(safeFetch('https://127.0.0.1:8888/'), /private/);
  await assert.rejects(safeFetch('https://localhost:8888/'), /private/);
  const r = await safeFetch('https://api.frankfurter.dev/v1/latest?base=EUR&symbols=USD');
  assert.equal(r.status, 200); assert.ok((await r.json()).rates.USD);
  console.log('net ok');
}
