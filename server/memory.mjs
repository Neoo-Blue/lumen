// Lumen's memory: one folder per person.
//   log.jsonl     every thing they said, tapped, connected, and every thing Lumen did, forever (until they forget it)
//   memory.json   who they are, how they like to interact, lasting facts, their look; rewritten by reflection
//   history.json  the live conversation, so it continues across devices and restarts
import { mkdir, readFile, writeFile, appendFile, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const root = () => process.env.LUMEN_DATA || new URL('./data', import.meta.url).pathname;
const dir = uid => `${root()}/users/${createHash('sha256').update(String(uid)).digest('hex').slice(0, 32)}`;
const readJSON = async (f, d) => { try { return JSON.parse(await readFile(f, 'utf8')); } catch { return d; } };

export async function load(uid, profile = {}) {
  const d = dir(uid);
  await mkdir(d, { recursive: true, mode: 0o700 });
  const mem = await readJSON(`${d}/memory.json`, { created: new Date().toISOString(), profile: '', style: '', facts: [], turns: 0, reflectedAt: 0 });
  Object.assign(mem, { email: profile.email || mem.email, name: profile.name || mem.name });
  return { dir: d, mem, history: sane(await readJSON(`${d}/history.json`, [])) }; // repairs a history saved before sane() existed
}

/**
 * A history the model service will accept: it starts with something the person said, every tool call is answered by
 * its result in the next message, and no result is left without its call. Cutting history at a fixed count (or a turn
 * that failed half way) can break those pairs, and a broken pair makes every later request fail, so this runs on save
 * and on load.
 */
export function sane(h, keep = 60) {
  let out = h.slice(-keep);
  const blocks = m => Array.isArray(m?.content) ? m.content : [];
  const plainUser = m => m?.role === 'user' && (typeof m.content === 'string' || !blocks(m).some(b => b.type === 'tool_result'));
  while (out.length && !plainUser(out[0])) out.shift();
  const fixed = [];
  for (let i = 0; i < out.length; i++) {
    const m = out[i];
    if (m.role === 'user' && blocks(m).some(b => b.type === 'tool_result')) {
      const prev = fixed[fixed.length - 1], calls = new Set(blocks(prev).filter(b => b.type === 'tool_use').map(b => b.id));
      const content = blocks(m).filter(b => b.type !== 'tool_result' || calls.has(b.tool_use_id));
      if (!content.length) continue;
      fixed.push({ ...m, content }); continue;
    }
    if (m.role === 'assistant' && blocks(m).some(b => b.type === 'tool_use')) {
      const next = out[i + 1], answered = new Set(blocks(next).filter(b => b.type === 'tool_result').map(b => b.tool_use_id));
      const missing = blocks(m).filter(b => b.type === 'tool_use' && !answered.has(b.id));
      fixed.push(m);
      if (missing.length) { // a turn that stopped half way: close each open call so the pair is whole
        const results = missing.map(b => ({ type: 'tool_result', tool_use_id: b.id, content: '(no result: the request was interrupted)', is_error: true }));
        if (next?.role === 'user' && blocks(next).some(b => b.type === 'tool_result')) { out[i + 1] = { ...next, content: [...blocks(next), ...results] }; }
        else fixed.push({ role: 'user', content: results });
      }
      continue;
    }
    fixed.push(m);
  }
  return fixed;
}

export const save = u => { const history = sane(u.history); return Promise.all([ // a copy: a request still running keeps adding to u.history
  writeFile(`${u.dir}/memory.json`, JSON.stringify(u.mem, null, 1), { mode: 0o600 }),
  writeFile(`${u.dir}/history.json`, JSON.stringify(history), { mode: 0o600 }),
]); };

// type: said | tapped | lumen | did | connected | look
export const log = (u, type, text) => appendFile(`${u.dir}/log.jsonl`, JSON.stringify({ t: new Date().toISOString(), type, text: String(text).slice(0, 2000) }) + '\n', { mode: 0o600 });

async function lines(u) {
  try { return (await readFile(`${u.dir}/log.jsonl`, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l)); } catch { return []; }
}

export async function recall(u, query, limit = 25) {
  const words = String(query).toLowerCase().split(/\s+/).filter(Boolean);
  const all = await lines(u);
  const hit = all.filter(e => { const s = e.text.toLowerCase(); return !words.length || words.some(w => s.includes(w)) || [...w0(words)].some(c => s.includes(c)); });
  return { total_events: all.length, since: all[0]?.t, matches: hit.slice(-limit).map(e => `${e.t.slice(0, 16).replace('T', ' ')} [${e.type}] ${e.text.slice(0, 300)}`) };
}
// The last few things asked and answered, for "continue where you left off" on another device.
export async function lastExchanges(u, n = 3) {
  const all = (await lines(u)).filter(e => e.type === 'said' || e.type === 'lumen'), out = [];
  for (let i = all.length - 1; i >= 0 && out.length < n; i--) if (all[i].type === 'said') out.push({ t: all[i].t, asked: all[i].text.slice(0, 140), answer: (all[i + 1]?.type === 'lumen' ? all[i + 1].text : '').slice(0, 160) });
  return out;
}
// Chinese has no spaces: also match 2-character pieces of each word
function* w0(words) { for (const w of words) if (/[一-鿿]/.test(w)) for (let i = 0; i + 2 <= w.length; i++) yield w.slice(i, i + 2); }

// What goes into every turn's context.
export async function context(u) {
  const m = u.mem, recent = (await lines(u)).slice(-14);
  const parts = [];
  if (m.name || m.email) parts.push(`Name: ${m.name || ''} ${m.email ? `<${m.email}>` : ''}`.trim());
  if (m.profile) parts.push(`Who they are: ${m.profile}`);
  if (m.style) parts.push(`How they like to interact: ${m.style}`);
  if (m.facts.length) parts.push(`Things to remember:\n- ${m.facts.map(f => f.text).join('\n- ')}`);
  if (m.theme) parts.push(`Their chosen look: ${JSON.stringify(m.theme)}`);
  if (recent.length) parts.push(`Most recent activity:\n${recent.map(e => `${e.t.slice(5, 16).replace('T', ' ')} [${e.type}] ${e.text.slice(0, 160)}`).join('\n')}`);
  parts.push(`Turns together so far: ${m.turns}. Known since ${m.created.slice(0, 10)}.`);
  return parts.join('\n').slice(0, 6000);
}

export function remember(u, text) {
  text = String(text).slice(0, 300);
  u.mem.facts = [...u.mem.facts.filter(f => f.text !== text), { t: new Date().toISOString(), text }].slice(-60);
}

// Every few turns, a model call rewrites the profile from what happened since last time.
export async function reflect(u, ask) {
  const all = await lines(u);
  const fresh = all.filter(e => Date.parse(e.t) > u.mem.reflectedAt).slice(-120);
  if (fresh.length < 4) return;
  const out = await ask(`You maintain the long-term memory of a personal AI operating system about ONE user.
Current memory:
PROFILE: ${u.mem.profile || '(empty)'}
STYLE: ${u.mem.style || '(empty)'}
FACTS: ${u.mem.facts.map(f => f.text).join(' | ') || '(none)'}

New activity since the last update:
${fresh.map(e => `${e.t.slice(0, 16)} [${e.type}] ${e.text.slice(0, 300)}`).join('\n')}

Reply with ONLY a JSON object: {"profile":"...","style":"...","facts":["..."]}
- profile: who they are, what they care about, people, places, projects, routines (max 600 characters, keep what still holds).
- style: how they like Lumen to talk, look and behave: language, length, tone, visual taste, what annoyed or pleased them (max 400 characters).
- facts: the COMPLETE list of lasting facts after this update: keep every existing fact that still holds, merge
  duplicates and near-duplicates into one, add new ones, drop what is no longer true (max 40, each short).
Write in the user's own language.`);
  const j = JSON.parse(out.slice(out.indexOf('{'), out.lastIndexOf('}') + 1));
  if (typeof j.profile === 'string') u.mem.profile = j.profile.slice(0, 800);
  if (typeof j.style === 'string') u.mem.style = j.style.slice(0, 600);
  if (Array.isArray(j.facts)) {
    const old = new Map(u.mem.facts.map(f => [f.text, f.t]));
    u.mem.facts = j.facts.filter(f => typeof f === 'string' && f.trim()).slice(0, 40).map(f => ({ t: old.get(f.trim()) || new Date().toISOString(), text: f.trim().slice(0, 300) }));
  }
  u.mem.reflectedAt = Date.parse(fresh.at(-1).t);
  await save(u);
}

export async function forget(u, what) {
  if (what === 'all') {
    await rm(u.dir, { recursive: true, force: true });
    await mkdir(u.dir, { recursive: true, mode: 0o700 });
    Object.assign(u.mem, { profile: '', style: '', facts: [], turns: 0, reflectedAt: 0, theme: undefined, created: new Date().toISOString() });
    u.history.length = 0;
  } else if (Number.isInteger(what)) u.mem.facts.splice(what, 1);
  else if (what === 'profile') { u.mem.profile = ''; u.mem.style = ''; }
  await save(u);
}

export const view = async u => ({ name: u.mem.name, email: u.mem.email, created: u.mem.created, turns: u.mem.turns, profile: u.mem.profile, style: u.mem.style, facts: u.mem.facts.map(f => f.text), events: (await lines(u)).length });

// node memory.mjs -> self-check
if (import.meta.url === `file://${process.argv[1]}`) {
  process.env.LUMEN_DATA = '/tmp/lumen-memtest';
  const { strict: assert } = await import('node:assert');
  const u = await load('test-user', { email: 'a@b.c' });
  await log(u, 'said', '帮我查一下去纽约的机票'); await log(u, 'did', 'flights SFO JFK'); await log(u, 'said', 'what is the weather in Tokyo');
  remember(u, '喜欢大字'); await save(u);
  const r = await recall(u, '纽约 机票');
  assert.equal(r.matches.length, 1); assert.match(r.matches[0], /纽约/);
  assert.equal((await recall(u, 'tokyo')).matches.length, 1);
  assert.match(await context(u), /喜欢大字/);
  const u2 = await load('test-user'); assert.equal(u2.mem.facts.length, 1); assert.equal(u2.mem.email, 'a@b.c');
  await forget(u2, 'all'); assert.equal((await recall(u2, '')).total_events, 0);
  console.log('memory ok');
}
