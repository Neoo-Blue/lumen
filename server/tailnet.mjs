// Tailscale on this machine, read through its local socket: is it signed in, what is this server's private address,
// and the sign-in link to show in the dashboard when it is not.
import http from 'node:http';

const SOCK = process.env.TS_SOCKET || '/var/run/tailscale/tailscaled.sock';

function local(path) {
  return new Promise(resolve => {
    const req = http.request({ socketPath: SOCK, path, headers: { host: 'local-tailscaled.sock' }, timeout: 3000 }, res => {
      let b = ''; res.on('data', c => b += c); res.on('end', () => { try { resolve(JSON.parse(b)); } catch { resolve(null); } });
    });
    req.on('error', () => resolve(null)); req.on('timeout', () => { req.destroy(); resolve(null); }); req.end();
  });
}

/** { state: 'missing'|'NeedsLogin'|'Running'|..., loginUrl, url (https://host.tailnet.ts.net), tailnet, user } */
export async function tailscale() {
  const s = await local('/localapi/v0/status');
  if (!s) return { state: 'missing' };
  const dns = String(s.Self?.DNSName || '').replace(/\.$/, '');
  const user = s.User?.[s.Self?.UserID]?.LoginName || '';
  return { state: s.BackendState, loginUrl: s.AuthURL || '', url: dns ? 'https://' + dns : '', tailnet: s.CurrentTailnet?.Name || '', user, certs: (s.CertDomains || []).length > 0 };
}
