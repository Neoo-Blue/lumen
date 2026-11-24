// Universal MCP client. One shared set (mcp.json, public servers for everyone) plus one set per visitor
// (what they connect themselves, including one-tap OAuth sign-in). Tools join the agent as "<server>__<tool>".
// Header and env values written as "env:NAME" are read from the environment, so no secret lives in the file.
import { readFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { SSEClientTransport } from '@modelcontextprotocol/sdk/client/sse.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { UnauthorizedError } from '@modelcontextprotocol/sdk/client/auth.js';
import { safeFetch } from './net.mjs';

const resolve = obj => Object.fromEntries(Object.entries(obj || {}).map(([k, v]) => [k, String(v).replace(/env:([A-Z0-9_]+)/g, (_, n) => process.env[n] ?? '')]));
const safe = s => String(s).replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 24);
const PUBLIC_URL = () => (process.env.LUMEN_PUBLIC_URL || `http://localhost:${process.env.PORT || 4321}`).replace(/\/$/, '');
const pendingAuth = new Map(); // oauth state -> { set, name, owner, exp }; a sign-in only completes for whoever started it
setInterval(() => { for (const [k, v] of pendingAuth) if (v.exp < Date.now()) pendingAuth.delete(k); }, 60e3).unref();

// Tokens live in memory only, per visitor, for this run. ponytail: re-sign-in after a restart; persist encrypted if that annoys testers.
class Auth {
  constructor() { this._state = randomBytes(24).toString('hex'); this.authUrl = null; }
  get redirectUrl() { return `${PUBLIC_URL()}/oauth/callback`; }
  get clientMetadata() { return { client_name: 'Lumen', redirect_uris: [this.redirectUrl], grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'], token_endpoint_auth_method: 'none' }; }
  state() { return this._state; }
  clientInformation() { return this.client; }
  saveClientInformation(c) { this.client = c; }
  tokens() { return this.tok; }
  saveTokens(t) { this.tok = t; }
  redirectToAuthorization(url) { this.authUrl = url.toString(); }
  saveCodeVerifier(v) { this.verifier = v; }
  codeVerifier() { return this.verifier; }
}

export class McpSet {
  // owner: who this set belongs to ('shared' for the servers everyone gets)
  constructor(owner = 'shared') { this.servers = new Map(); this.owner = owner; }

  transportFor(s) {
    const c = s.cfg;
    // headers and env are used exactly as given: only loadFile (the owner's own mcp.json) expands env:NAME
    if (c.command) return new StdioClientTransport({ command: c.command, args: c.args || [], env: { ...process.env, ...(c.env || {}) }, stderr: 'ignore' });
    const opts = { requestInit: { headers: c.headers || {} }, fetch: (u, i) => safeFetch(u, i), ...(s.auth ? { authProvider: s.auth } : {}) };
    return c.transport === 'sse' || /\/sse\/?$/.test(c.url) ? new SSEClientTransport(new URL(c.url), opts) : new StreamableHTTPClientTransport(new URL(c.url), opts);
  }

  // -> { name, tools, error?, auth_url? }
  async connect(name, cfg) {
    name = safe(name);
    const old = this.servers.get(name); if (old) await old.client?.close().catch(() => {});
    const s = { cfg, tools: [], error: null, auth: cfg.command ? null : (old?.auth?.tok ? old.auth : new Auth()) };
    this.servers.set(name, s);
    for (let attempt = 0; attempt < 2; attempt++) {
      s.client = new Client({ name: 'lumen', version: '0.3.0' });
      s.transport = this.transportFor(s);
      try {
        await Promise.race([s.client.connect(s.transport), new Promise((_, rej) => setTimeout(() => rej(new Error('connect timeout')), 15000))]);
        s.tools = (await s.client.listTools()).tools.slice(0, cfg.maxTools || 40);
        s.error = null;
        return { name, tools: s.tools.length };
      } catch (e) {
        if (s.auth?.authUrl && (e instanceof UnauthorizedError || /unauthori|401/i.test(e.message))) {
          pendingAuth.set(s.auth.state(), { set: this, name, owner: this.owner, exp: Date.now() + 10 * 60e3 });
          s.error = 'needs_auth';
          return { name, tools: 0, auth_url: s.auth.authUrl };
        }
        s.error = /private|https/i.test(e.message) ? 'address not allowed' : /timeout/i.test(e.message) ? 'timed out' : /40[13]/.test(e.message) ? 'refused' : 'could not connect';
        if (cfg.transport || cfg.command || attempt) break;
        cfg = { ...cfg, transport: 'sse' }; s.cfg = cfg; // some servers only speak the older SSE transport
      }
    }
    return { name, tools: 0, error: s.error };
  }

  async loadFile(file) {
    let conf = { servers: {} };
    try { conf = JSON.parse(await readFile(file, 'utf8')); } catch {}
    await Promise.all(Object.entries(conf.servers || {}).filter(([, c]) => !c.disabled).map(([n, c]) => this.connect(n, { ...c, headers: resolve(c.headers), env: resolve(c.env) })));
  }

  status() {
    return [...this.servers].map(([name, s]) => ({ name, label: s.cfg.label || name, tools: s.tools.length, error: s.error === 'needs_auth' ? null : s.error, pending: s.error === 'needs_auth' }));
  }

  toolDefs() {
    const defs = [];
    for (const [name, s] of this.servers) for (const t of s.tools) defs.push({
      name: `${name}__${t.name.replace(/[^a-zA-Z0-9_-]/g, '_')}`.slice(0, 64),
      description: `[${s.cfg.label || name}] ${(t.description || t.name).replace(/\s+/g, ' ').slice(0, 300)}`,
      input_schema: t.inputSchema?.type === 'object' ? t.inputSchema : { type: 'object', properties: {} },
    });
    return defs;
  }

  /** True when the server marks the tool read-only, or its name only reads (get, list, search...): those run without asking. */
  readOnly(toolName) {
    const s = this.servers.get(toolName.split('__')[0]);
    const tool = s?.tools.find(t => `${toolName.split('__')[0]}__${t.name.replace(/[^a-zA-Z0-9_-]/g, '_')}`.slice(0, 64) === toolName);
    if (!tool) return false;
    if (tool.annotations?.readOnlyHint === true) return true;
    if (tool.annotations?.readOnlyHint === false || tool.annotations?.destructiveHint === true) return false;
    return /^(get|list|search|read|fetch|find|query|describe|show|lookup|resolve|ask|view|count|check)([_-]|[A-Z]|$)/.test(tool.name) && !/(create|delete|remove|send|update|edit|write|post|buy|sell|pay|book|transfer|cancel|archive|move|invite|publish|merge|charge|refund|set)/i.test(tool.name);
  }

  owns(toolName) { return toolName.includes('__') && this.servers.get(toolName.split('__')[0])?.tools.length > 0; }

  async call(toolName, input) {
    const s = this.servers.get(toolName.split('__')[0]);
    const tool = s.tools.find(t => `${toolName.split('__')[0]}__${t.name.replace(/[^a-zA-Z0-9_-]/g, '_')}`.slice(0, 64) === toolName);
    if (!tool) return { error: `no such tool ${toolName}` };
    const r = await s.client.callTool({ name: tool.name, arguments: input || {} }, undefined, { timeout: 45000 });
    const text = (r.content || []).map(c => c.type === 'text' ? c.text : c.type === 'resource' ? c.resource?.text || '' : `[${c.type}]`).join('\n');
    return { ...(r.isError ? { error: true } : {}), result: (text || JSON.stringify(r.structuredContent || {})).slice(0, 12000) };
  }
}

// OAuth redirect lands here: finish the exchange, then reconnect with the new tokens.
export async function finishAuth(state, code, owner) {
  const p = pendingAuth.get(state);
  if (!p || p.exp < Date.now() || p.owner !== owner) return { error: 'unknown or expired sign-in' };
  pendingAuth.delete(state);
  const s = p.set.servers.get(p.name);
  await s.transport.finishAuth(code);
  s.auth.authUrl = null;
  return p.set.connect(p.name, s.cfg);
}
