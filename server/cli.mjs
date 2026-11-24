// Subscription brains: Lumen drives the official command-line tools a person is already signed in to (Claude Code
// with Claude Pro or Max, Codex with ChatGPT, Gemini CLI, OpenCode, Antigravity), one turn per call, with the tools'
// own abilities switched off. Lumen keeps its own tools: the model asks for them in <tool_call> blocks, which are
// parsed back into ordinary tool calls (the same protocol as a Messages API tool call).
// Each tool keeps its sign-in in its own private folder under the data folder; nothing touches the server's home.
import { spawn } from 'node:child_process';
import { mkdirSync, existsSync, writeFileSync, readFileSync, rmSync, mkdtempSync } from 'node:fs';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
const DATA = process.env.LUMEN_DATA || './data'; // the same folder config.mjs uses (imported there, so not imported back)

const ROOT = join(DATA, 'cli');
const PREFIX = join(ROOT, 'npm'); // tools installed on demand live here
mkdirSync(ROOT, { recursive: true, mode: 0o700 });

export const CLIS = {
  claude: { label: 'Claude Code', plan: 'Claude Pro or Max', pkg: '@anthropic-ai/claude-code', bin: 'claude', model: 'claude-opus-5-5',
    login: ['setup-token'], hint: 'Signs in with your Claude account and keeps a long-lived token for this server.' },
  codex: { label: 'Codex', plan: 'ChatGPT Plus, Pro or Business', pkg: '@openai/codex', bin: 'codex', model: '',
    login: ['login', '--device-auth'], hint: 'Shows a code; enter it on the page that opens, signed in to ChatGPT.' },
  gemini: { label: 'Gemini CLI', plan: 'Google AI Pro, Ultra or the free tier', pkg: '@google/gemini-cli', bin: 'gemini', model: '',
    login: [], loginEnv: { NO_BROWSER: 'true' }, hint: 'Choose "Sign in with Google", open the link, and paste the code back here.' },
  opencode: { label: 'OpenCode', plan: 'any provider OpenCode signs in to', pkg: 'opencode-ai', bin: 'opencode', model: 'anthropic/claude-opus-5-5',
    login: ['auth', 'login'], hint: 'Pick a provider, then follow its sign-in.' },
  antigravity: { label: 'Antigravity', plan: 'Google Antigravity', pkg: null, bin: 'agy', model: '',
    login: ['login'], hint: 'Antigravity is not on npm: install its command-line tool on this server first.' },
};

const home = id => { const d = join(ROOT, id); mkdirSync(d, { recursive: true, mode: 0o700 }); return d; };
const binOf = id => { const own = join(PREFIX, 'node_modules', '.bin', CLIS[id].bin); return existsSync(own) ? own : CLIS[id].bin; };
const tokenFile = id => join(home(id), 'token');

/** The environment a tool runs in: its own home, none of the server's secrets. */
function envFor(id, extra = {}) {
  const keep = ['PATH', 'LANG', 'TZ', 'TERM', 'SSL_CERT_FILE', 'NODE_EXTRA_CA_CERTS'];
  const env = Object.fromEntries(keep.filter(k => process.env[k]).map(k => [k, process.env[k]]));
  env.HOME = home(id); env.XDG_CONFIG_HOME = join(env.HOME, '.config'); env.XDG_DATA_HOME = join(env.HOME, '.local/share');
  if (id === 'codex') { env.CODEX_HOME = join(env.HOME, '.codex'); mkdirSync(env.CODEX_HOME, { recursive: true, mode: 0o700 }); }
  if (id === 'claude' && existsSync(tokenFile(id))) env.CLAUDE_CODE_OAUTH_TOKEN = readFileSync(tokenFile(id), 'utf8').trim();
  return { ...env, ...extra };
}

function run(cmd, args, { env, input, timeoutMs = 300000, cwd } = {}) {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args, { env, cwd, stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '', err = '';
    const t = setTimeout(() => { p.kill('SIGKILL'); reject(new Error(`${cmd} took too long`)); }, timeoutMs);
    p.stdout.on('data', d => out += d); p.stderr.on('data', d => err += d);
    p.on('error', e => { clearTimeout(t); reject(e.code === 'ENOENT' ? new Error(`${cmd} is not installed on this server`) : e); });
    p.on('close', code => { clearTimeout(t); if (code === 0) return resolve({ out, err });
      let why = ''; try { const j = JSON.parse(out); why = j.result || j.error?.message || j.error || ''; } catch {} // tools that report errors as JSON
      reject(new Error(String(why || (err || out).trim().split('\n').slice(-3).join(' ')).slice(0, 400) || `${cmd} exited with ${code}`)); });
    if (input != null) p.stdin.end(input); else p.stdin.end();
  });
}

// ---------- the conversation as one prompt, and tool calls back out ----------
const PROTOCOL = `TOOLS: you can use the tools listed below. To use one, write only the call, in this exact form, and nothing else:
<tool_call>{"name": "tool_name", "input": { ...arguments matching its schema... }}</tool_call>
You may write several <tool_call> blocks. Their results come back in the next turn as <tool_result id="..."> blocks.
When no tool is needed, answer in plain text with no <tool_call> blocks. Never invent tool names or results.`;

const render = c => typeof c === 'string' ? c : Array.isArray(c) ? c.map(b =>
  b.type === 'text' ? b.text : b.type === 'tool_use' ? `<tool_call>${JSON.stringify({ id: b.id, name: b.name, input: b.input })}</tool_call>`
  : b.type === 'tool_result' ? `<tool_result id="${b.tool_use_id}">\n${render(b.content)}\n</tool_result>` : b.type === 'image' ? '[picture attached]' : '').filter(Boolean).join('\n') : '';

export function toPrompt(messages, tools) {
  const parts = [];
  if (tools?.length) parts.push(PROTOCOL, 'AVAILABLE TOOLS:\n\n' + tools.filter(t => t.input_schema).map(t => `### ${t.name}\n${(t.description || '').trim()}\nInput schema: ${JSON.stringify(t.input_schema)}`).join('\n\n'));
  parts.push('CONVERSATION:\n' + messages.map(m => `[${m.role === 'assistant' ? 'Assistant' : 'User'}]\n${render(m.content)}`).join('\n\n'), '[Assistant]');
  return parts.join('\n\n');
}

export function imagesOf(messages, keep = 3) {
  const found = [], walk = n => Array.isArray(n) ? n.forEach(walk) : n && typeof n === 'object' && (n.type === 'image' && n.source?.type === 'base64' ? found.push(n.source) : walk(n.content));
  messages.forEach(m => walk(m.content));
  return found.slice(-keep);
}

const CALL = /<tool_call>\s*(\{[\s\S]*?\})\s*<\/tool_call>/g, INVOKE = /<invoke\s+name="([^"]+)"\s*>([\s\S]*?)<\/invoke>/g, PARAM = /<parameter\s+name="([^"]+)"\s*>([\s\S]*?)<\/parameter>/g;
/** Text from the tool, as Messages API content blocks: text plus tool_use. */
export function parseReply(text) {
  const content = [], calls = [];
  let rest = text.replace(CALL, (_, raw) => { try { const o = JSON.parse(raw); if (o?.name) calls.push({ name: String(o.name), input: o.input && typeof o.input === 'object' ? o.input : {} }); } catch {} return ''; });
  rest = rest.replace(INVOKE, (_, name, body) => { const input = {}; for (const [, k, v] of body.matchAll(PARAM)) { try { input[k] = JSON.parse(v.trim()); } catch { input[k] = v.trim(); } } calls.push({ name, input }); return ''; })
    .replace(/<\/?function_calls>/g, '').trim();
  if (rest) content.push({ type: 'text', text: rest });
  for (const c of calls) content.push({ type: 'tool_use', id: 'toolu_' + randomBytes(12).toString('hex'), name: c.name, input: c.input });
  return { role: 'assistant', content, stop_reason: calls.length ? 'tool_use' : 'end_turn' };
}

// ---------- one turn through each tool ----------
async function claudeTurn({ system, prompt, images, model }) {
  const args = ['-p', '--model', model, '--tools', '', '--max-turns', '1', '--no-session-persistence', '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}',
    '--setting-sources', '', '--disable-slash-commands', '--system-prompt', system || 'Do what the user asks.'];
  if (!images.length) return JSON.parse((await run(binOf('claude'), [...args, '--output-format', 'json'], { env: envFor('claude'), input: prompt })).out).result || '';
  const msg = { type: 'user', message: { role: 'user', content: [{ type: 'text', text: prompt }, ...images.map(s => ({ type: 'image', source: s }))] } };
  const { out } = await run(binOf('claude'), [...args, '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose'], { env: envFor('claude'), input: JSON.stringify(msg) + '\n' });
  const last = out.trim().split('\n').map(l => { try { return JSON.parse(l); } catch { return null; } }).filter(x => x?.type === 'result').pop();
  return last?.result || '';
}

function scratch(images) { // an empty folder for tools that work on files, with any pictures written into it
  const dir = mkdtempSync(join(ROOT, 'turn-')), files = images.map((s, i) => { const f = join(dir, `picture-${i + 1}.${(s.media_type || 'image/jpeg').split('/')[1].replace('jpeg', 'jpg')}`); writeFileSync(f, Buffer.from(s.data, 'base64')); return f; });
  return { dir, files, done: () => rmSync(dir, { recursive: true, force: true }) };
}

async function codexTurn({ system, prompt, images, model }) {
  const s = scratch(images), outFile = join(s.dir, 'reply.txt');
  try {
    await run(binOf('codex'), ['exec', '--skip-git-repo-check', '--ephemeral', '--ignore-user-config', '--sandbox', 'read-only', '-C', s.dir, '-o', outFile,
      ...(model ? ['-m', model] : []), ...s.files.flatMap(f => ['-i', f]), '-'], { env: envFor('codex'), input: `${system}\n\n${prompt}`, cwd: s.dir });
    return readFileSync(outFile, 'utf8');
  } finally { s.done(); }
}

async function geminiTurn({ system, prompt, images, model }) {
  const s = scratch(images);
  try {
    const pics = s.files.map(f => '@' + f.split('/').pop()).join(' ');
    const { out } = await run(binOf('gemini'), ['-p', pics || 'Continue.', '-o', 'json', '--approval-mode', 'plan', '--skip-trust', ...(model ? ['-m', model] : [])], { env: envFor('gemini'), input: `${system}\n\n${prompt}`, cwd: s.dir });
    return JSON.parse(out).response || '';
  } finally { s.done(); }
}

async function opencodeTurn({ system, prompt, images, model }) {
  const s = scratch(images), file = join(s.dir, 'conversation.md');
  writeFileSync(file, `${system}\n\n${prompt}`);
  try {
    const { out } = await run(binOf('opencode'), ['run', '--format', 'json', '--dir', s.dir, ...(model ? ['-m', model] : []), '-f', file, ...s.files.flatMap(f => ['-f', f]),
      'Follow the instructions and continue the conversation in the attached conversation.md. Reply with the assistant\'s next message only.'], { env: envFor('opencode'), cwd: s.dir });
    return out.split('\n').map(l => { try { return JSON.parse(l); } catch { return null; } }).filter(e => e?.type === 'text' && e.part?.text).map(e => e.part.text).join('');
  } finally { s.done(); }
}

async function antigravityTurn({ system, prompt, model }) {
  const s = scratch([]);
  try {
    const { out } = await run(binOf('antigravity'), ['-p', '--output-format', 'json', '--mode', 'plan', '--disable-slash-commands', ...(model ? ['--model', model] : [])], { env: envFor('antigravity'), input: `${system}\n\n${prompt}`, cwd: s.dir });
    try { const j = JSON.parse(out); return j.result || j.response || j.text || out; } catch { return out; }
  } finally { s.done(); }
}

const TURN = { claude: claudeTurn, codex: codexTurn, gemini: geminiTurn, opencode: opencodeTurn, antigravity: antigravityTurn };

/** A brain with the same shape as brain.mjs: stream(params, onText) and create(params), Messages API in and out. */
export function makeCliBrain(cfg) {
  const id = CLIS[cfg.cli] ? cfg.cli : 'claude', model = cfg.model || CLIS[id].model;
  const call = async (params, onText) => {
    const system = typeof params.system === 'string' ? params.system : render(params.system);
    const text = await TURN[id]({ system, prompt: toPrompt(params.messages, params.tools), images: imagesOf(params.messages), model });
    const msg = parseReply(text);
    const said = msg.content.filter(b => b.type === 'text').map(b => b.text).join('');
    if (said && onText) onText(said);
    return msg;
  };
  return { model: model || id, webSearch: false, stream: call, create: params => call(params) };
}

// ---------- setup: install, sign in, status ----------
export async function install(id) {
  if (!CLIS[id]?.pkg) throw new Error(`${CLIS[id]?.label || id} cannot be installed automatically`);
  mkdirSync(PREFIX, { recursive: true });
  await run('npm', ['install', '--prefix', PREFIX, '--no-audit', '--no-fund', CLIS[id].pkg + '@latest'], { env: { ...process.env, HOME: ROOT }, timeoutMs: 600000 });
}

export async function status(id) {
  const c = CLIS[id]; let installed = false, version = '';
  try { version = (await run(binOf(id), ['--version'], { env: envFor(id), timeoutMs: 20000 })).out.trim().split('\n')[0].slice(0, 60); installed = true; } catch {}
  const h = home(id), signedIn = id === 'claude' ? existsSync(tokenFile(id))
    : id === 'codex' ? existsSync(join(h, '.codex', 'auth.json'))
    : id === 'gemini' ? existsSync(join(h, '.gemini', 'oauth_creds.json'))
    : id === 'opencode' ? existsSync(join(h, '.local/share/opencode/auth.json'))
    : installed;
  return { id, label: c.label, plan: c.plan, hint: c.hint, installable: !!c.pkg, installed, version, signedIn };
}

/** Sign-in runs the tool's own login in a terminal-like session; the dashboard shows its output and can type into it. */
let session = null;
const strip = s => s.replace(/\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*\x07|\r/g, '');
export function loginStart(id) {
  loginStop();
  const c = CLIS[id], env = envFor(id, { ...(c.loginEnv || {}), TERM: 'xterm-256color', COLUMNS: '200' });
  const cmd = [binOf(id), ...c.login].map(a => `'${a.replace(/'/g, "'\\''")}'`).join(' ');
  // `script` gives the tool a terminal, which some sign-in screens need; plain pipes otherwise
  const useScript = existsSync('/usr/bin/script');
  const p = useScript ? spawn('script', ['-qfec', cmd, '/dev/null'], { env }) : spawn(binOf(id), c.login, { env });
  session = { id, p, out: '', running: true, code: null };
  const add = d => { session.out = (session.out + strip(String(d))).slice(-20000);
    const tok = id === 'claude' && session.out.match(/\b(sk-ant-oat[\w-]{20,})/); if (tok) writeFileSync(tokenFile('claude'), tok[1], { mode: 0o600 }); };
  p.stdout.on('data', add); p.stderr.on('data', add);
  p.on('close', code => { if (session?.p === p) { session.running = false; session.code = code; } });
  p.on('error', e => { if (session?.p === p) { session.running = false; session.out += '\n' + e.message; } });
  setTimeout(() => { if (session?.p === p && session.running) loginStop(); }, 15 * 60000);
  return loginState();
}
export function loginInput(text) { if (session?.running) session.p.stdin.write(String(text).slice(0, 2000) + '\r'); return loginState(); }
export function loginStop() { if (session?.running) try { session.p.kill(); } catch {} }
export function loginState() {
  if (!session) return { running: false, out: '' };
  // the Claude token is kept, never shown
  return { id: session.id, running: session.running, code: session.code, out: session.out.replace(/sk-ant-oat[\w-]{20,}/g, '[token saved]').slice(-6000), links: [...new Set(session.out.match(/https:\/\/[^\s"'<>]+/g) || [])].slice(-3) };
}
/** For people who signed in on their own computer: the tool's credentials file, uploaded through the dashboard. */
export function saveCredentials(id, text) {
  const where = { codex: '.codex/auth.json', gemini: '.gemini/oauth_creds.json', opencode: '.local/share/opencode/auth.json' }[id];
  if (id === 'claude') { const t = String(text).trim(); if (!/^sk-ant-oat[\w-]{20,}$/.test(t)) throw new Error('that is not a Claude Code token (it starts with sk-ant-oat)'); writeFileSync(tokenFile(id), t, { mode: 0o600 }); return; }
  if (!where) throw new Error('this tool signs in on the server only');
  JSON.parse(text); // must be the JSON file itself
  const f = join(home(id), where); mkdirSync(join(f, '..'), { recursive: true, mode: 0o700 }); writeFileSync(f, text, { mode: 0o600 });
}
