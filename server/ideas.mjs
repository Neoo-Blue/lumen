// Ideas, captured the moment they are said: a fast model decides which notebook a thought belongs in (a Notion
// database or page, a folder or note in the person's Obsidian vault on the phone, or Lumen's own Idea Box), tidies it
// without changing what it says, and files it. Every capture is also kept in a log, so "what were my ideas this week"
// and "ideas about the product" are a read, not a search through notebooks.
import { readFile, writeFile } from 'node:fs/promises';

const NOTION = 'https://api.notion.com/v1';
export const freshIdeas = () => ({ notion: '', notionName: '', rules: '', dests: [], destsAt: 0, vault: '' });

export function makeIdeas({ vault, light, deviceAsk, relayLive, save, fetchImpl = fetch }) {
  const of = p => (p.state.ideas ||= freshIdeas());
  const logFile = p => `${p.u.dir}/ideas-log.json`;
  const loadLog = async p => { try { return JSON.parse(await readFile(logFile(p), 'utf8')); } catch { return []; } };
  const notion = async (token, path, body, method = body ? 'POST' : 'GET') => {
    const r = await fetchImpl(NOTION + path, { method, headers: { authorization: `Bearer ${token}`, 'notion-version': '2022-06-28', 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(12000) });
    const j = await r.json().catch(() => ({})); if (!r.ok) throw new Error(j.message || `notion ${r.status}`); return j;
  };
  const plain = rich => (rich || []).map(x => x.plain_text || '').join('').trim();

  /** Every place an idea can go, refreshed every ten minutes: the Idea Box, then Notion, then the vault. */
  async function destinations(p, force = false) {
    const I = of(p);
    if (!force && I.dests.length && Date.now() - I.destsAt < 600e3) return I.dests;
    const out = [{ id: 'box', kind: 'box', name: 'Idea Box', about: "Lumen's own list, when nothing else fits" }];
    if (I.notion) try {
      // databases and standalone pages are asked for apart: a busy database's rows would otherwise fill every result
      const tok = vault.unseal(I.notion), q = kind => notion(tok, '/search', { page_size: 50, filter: { property: 'object', value: kind }, sort: { direction: 'descending', timestamp: 'last_edited_time' } });
      const [dbs, pages] = await Promise.all([q('database'), q('page')]);
      for (const r of [...(dbs.results || []), ...(pages.results || [])]) {
        if (r.object === 'database') { const tp = Object.entries(r.properties || {}).find(([, v]) => v.type === 'title')?.[0]; if (tp) out.push({ id: 'n:' + r.id, kind: 'notion-db', name: plain(r.title) || 'Untitled database', titleProp: tp, about: plain(r.description) }); }
        else if (r.object === 'page' && r.parent?.type !== 'database_id') { const t = Object.values(r.properties || {}).find(v => v.type === 'title'); out.push({ id: 'n:' + r.id, kind: 'notion-page', name: plain(t?.title) || 'Untitled page' }); }
      }
    } catch (e) { console.error('ideas notion', e.message); }
    if (I.vault && relayLive(p.who.uid)) {
      const v = await deviceAsk(p, 'vaultList', {}, 12000).catch(() => null);
      if (v?.ok) {
        for (const f of (v.data.folders || []).slice(0, 40)) out.push({ id: 'vf:' + f, kind: 'vault-folder', name: `${v.data.name || 'Vault'}/${f}`, path: f });
        for (const n of (v.data.notes || []).slice(0, 60)) out.push({ id: 'vn:' + n, kind: 'vault-note', name: `${v.data.name || 'Vault'}/${n.replace(/\.md$/i, '')}`, path: n });
      } else if (I.dests.some(d => d.kind?.startsWith('vault'))) out.push(...I.dests.filter(d => d.kind?.startsWith('vault'))); // the phone is away: keep the last list
    }
    I.dests = out.slice(0, 160); I.destsAt = Date.now(); save(p);
    return I.dests;
  }

  /** The fast model picks the place and tidies the words; nothing is invented, the person's language is kept. */
  async function classify(p, text, dests) {
    const I = of(p), list = dests.map((d, i) => `${i}. [${d.kind}] ${d.name}${d.about ? ' (' + d.about.slice(0, 80) + ')' : ''}`).join('\n');
    const prompt = `Someone just said this to their phone assistant, to keep it as an idea or note:\n"""${text}"""\n\nWhere it can go:\n${list}\n${I.rules ? `\nTheir own rules for sorting (follow these first):\n${I.rules}\n` : ''}
Pick the single best place. A note fits a vault folder or a database when it is about that topic; append to a vault note only when it clearly continues that note (a running list, a journal, an inbox). If nothing fits well, pick 0 (Idea Box).
Answer with JSON only: {"to": <number>, "title": "<3 to 8 words, their language>", "text": "<their words, cleaned of filler and speech-to-text slips, same language, nothing added>", "tags": ["<1 to 3 short tags>"]}`;
    const raw = await light(prompt, 600);
    const m = String(raw).match(/\{[\s\S]*\}/); let j = {}; try { j = JSON.parse(m?.[0] || '{}'); } catch {}
    const d = dests[Number.isInteger(j.to) && j.to >= 0 && j.to < dests.length ? j.to : 0];
    return { dest: d, title: String(j.title || text.slice(0, 40)).slice(0, 80), text: String(j.text || text).slice(0, 4000), tags: (Array.isArray(j.tags) ? j.tags : []).map(t => String(t).replace(/[^\p{L}\p{N}_-]/gu, '')).filter(Boolean).slice(0, 3) };
  }

  async function write(p, d, it) {
    const I = of(p), when = new Date().toLocaleString('sv-SE', { timeZone: p.state.routines?.tz || 'America/Los_Angeles' }).slice(0, 16);
    if (d.kind === 'notion-db') {
      await notion(vault.unseal(I.notion), '/pages', { parent: { database_id: d.id.slice(2) }, properties: { [d.titleProp]: { title: [{ text: { content: it.title } }] } },
        children: [{ object: 'block', type: 'paragraph', paragraph: { rich_text: [{ type: 'text', text: { content: it.text } }] } }, ...(it.tags.length ? [{ object: 'block', type: 'paragraph', paragraph: { rich_text: [{ type: 'text', text: { content: it.tags.map(t => '#' + t).join(' ') }, annotations: { color: 'gray' } }] } }] : [])] });
    } else if (d.kind === 'notion-page') {
      await notion(vault.unseal(I.notion), `/blocks/${d.id.slice(2)}/children`, { children: [{ object: 'block', type: 'bulleted_list_item', bulleted_list_item: { rich_text: [{ type: 'text', text: { content: `${it.title}: ` }, annotations: { bold: true } }, { type: 'text', text: { content: `${it.text} (${when})` } }] } }] }, 'PATCH');
    } else if (d.kind === 'vault-folder' || d.kind === 'vault-note') {
      const append = d.kind === 'vault-note';
      const body = append ? `- ${when} **${it.title}**: ${it.text}${it.tags.length ? ' ' + it.tags.map(t => '#' + t).join(' ') : ''}`
        : `---\ncreated: ${when}\ntags: [${it.tags.join(', ')}]\nsource: Lumen\n---\n\n# ${it.title}\n\n${it.text}\n`;
      const path = append ? d.path : `${d.path}/${it.title.replace(/[\\/:*?"<>|#^[\]]/g, '').slice(0, 60) || 'Idea'}.md`;
      const r = await deviceAsk(p, 'vaultWrite', { path, text: body, mode: append ? 'append' : 'create' }, 20000);
      if (!r?.ok) throw new Error(r?.error || 'the phone did not answer');
    }
  }

  /** Capture one idea: place, tidy, file, log. Falls back to the Idea Box if the chosen place cannot be written. */
  async function capture(p, text) {
    text = String(text || '').trim().slice(0, 4000); if (!text) return { error: 'nothing to keep' };
    const dests = await destinations(p);
    const it = await classify(p, text, dests).catch(() => ({ dest: dests[0], title: text.slice(0, 40), text, tags: [] }));
    let d = it.dest, note = '';
    if (d.kind !== 'box') try { await write(p, d, it); } catch (e) { note = `could not write to ${d.name} (${e.message}); kept in the Idea Box`; d = dests[0]; }
    const log = await loadLog(p); log.push({ t: new Date().toISOString(), title: it.title, text: it.text, tags: it.tags, to: d.name, kind: d.kind, said: text });
    await writeFile(logFile(p), JSON.stringify(log.slice(-3000)), { mode: 0o600 });
    return { title: it.title, text: it.text, tags: it.tags, to: d.name, kind: d.kind, note };
  }

  // ---------- the AI's view: what was captured (for summaries and questions), and capturing from a conversation ----------
  const TOOL = { name: 'ideas', description: "The person's captured ideas and notes (each also filed in their Notion, Obsidian vault or Idea Box). action recent: days (default 7), query (optional words), returns what was captured with where it went; use it for 'summarize my ideas this week' or 'what ideas do I have about X'. action capture: text (file a new idea now, the same way as when they say it).", input_schema: { type: 'object', properties: { action: { type: 'string' }, days: { type: 'number' }, query: { type: 'string' }, text: { type: 'string' } }, required: ['action'] } };
  async function tool(p, i) {
    if (i.action === 'capture') { const r = await capture(p, i.text); return { data: r, ui: r.error ? [] : [{ c: 'done', text: `Kept: ${r.title} → ${r.to}` }] }; }
    const since = Date.now() - Math.min(+i.days || 7, 365) * 864e5, words = String(i.query || '').toLowerCase().split(/\s+/).filter(Boolean);
    const rows = (await loadLog(p)).filter(x => Date.parse(x.t) > since && words.every(w => `${x.title} ${x.text} ${x.tags.join(' ')} ${x.to}`.toLowerCase().includes(w)));
    return { data: { count: rows.length, ideas: rows.slice(-80).map(x => `${x.t.slice(0, 10)} [${x.to}] ${x.title}: ${x.text}`.slice(0, 400)) } };
  }

  // ---------- settings ----------
  async function settings(p, b) {
    const I = of(p);
    if (b.action === 'notion') {
      const token = String(b.token || '').trim();
      if (!/^(secret_|ntn_)[A-Za-z0-9]{20,}$/.test(token)) return { error: 'paste the internal integration secret (it starts with ntn_ or secret_)' };
      try { const me = await notion(token, '/users/me'); I.notion = vault.seal(token); I.notionName = me.bot?.workspace_name || me.name || 'Notion'; } catch (e) { return { error: 'Notion did not accept that secret' }; }
      I.destsAt = 0;
    }
    if (b.action === 'notionOff') { I.notion = ''; I.notionName = ''; I.destsAt = 0; }
    if (b.action === 'vault' && typeof b.name === 'string') { I.vault = b.name.slice(0, 80); I.destsAt = 0; }
    if (b.action === 'vaultOff') { I.vault = ''; I.destsAt = 0; }
    if (b.action === 'rules' && typeof b.rules === 'string') I.rules = b.rules.slice(0, 1500);
    save(p);
    return view(p, b.action === 'refresh' || b.action === 'notion' || b.action === 'vault');
  }
  async function view(p, refresh = false) {
    const I = of(p), d = refresh ? await destinations(p, true).catch(() => I.dests) : I.dests;
    return { notion: I.notionName || '', vault: I.vault || '', rules: I.rules || '', places: d.length, recent: (await loadLog(p)).slice(-5).reverse().map(x => ({ title: x.title, to: x.to, t: x.t })) };
  }

  return { capture, tool, TOOL, settings, view, destinations };
}

if (globalThis.process?.argv?.[1]?.endsWith('ideas.mjs')) {
  const assert = await import('node:assert/strict'), { mkdtemp } = await import('node:fs/promises'), { tmpdir } = await import('node:os');
  const calls = [], phone = [];
  const fakeFetch = async (url, o) => { calls.push([url.replace(NOTION, ''), o.method, o.body && JSON.parse(o.body)]);
    const want = o.body && JSON.parse(o.body).filter?.value;
    const j = url.endsWith('/search') ? { results: [{ object: 'database', id: 'db1', title: [{ plain_text: 'Product ideas' }], properties: { Name: { type: 'title' } } }, { object: 'page', id: 'pg1', parent: { type: 'workspace' }, properties: { title: { type: 'title', title: [{ plain_text: 'Reading list' }] } } }].filter(r => !want || r.object === want) } : url.endsWith('/users/me') ? { bot: { workspace_name: 'Home' } } : { id: 'new' };
    return { ok: true, json: async () => j }; };
  let pick = { to: 1, title: '语音记账', text: '做一个用语音记账的小功能', tags: ['产品'] };
  const ideas = makeIdeas({ vault: { seal: s => 'S' + s, unseal: s => s.slice(1) }, light: async () => JSON.stringify(pick), relayLive: () => true, save: () => {}, fetchImpl: fakeFetch,
    deviceAsk: async (_, m, a) => { phone.push([m, a]); return m === 'vaultList' ? { ok: true, data: { name: 'Brain', folders: ['Journal', 'Work'], notes: ['Inbox.md'] } } : { ok: true, data: {} }; } });
  const p = { who: { uid: 'u' }, u: { dir: await mkdtemp(tmpdir() + '/ideas-') }, state: {} };
  assert.equal((await ideas.settings(p, { action: 'notion', token: 'ntn_' + 'x'.repeat(30) })).notion, 'Home');
  await ideas.settings(p, { action: 'vault', name: 'Brain' });
  const ds = await ideas.destinations(p, true);
  assert.deepEqual(ds.map(d => d.kind), ['box', 'notion-db', 'notion-page', 'vault-folder', 'vault-folder', 'vault-note']);
  let r = await ideas.capture(p, '嗯我有个想法 做一个用语音记账的小功能');
  assert.equal(r.to, 'Product ideas'); assert.equal(calls.at(-1)[0], '/pages'); assert.equal(calls.at(-1)[2].parent.database_id, 'db1');
  pick = { to: 5, title: 'Call the dentist', text: 'call the dentist friday', tags: [] };
  r = await ideas.capture(p, 'note: call the dentist friday'); assert.equal(r.kind, 'vault-note');
  assert.deepEqual(phone.at(-1)[1].mode, 'append'); assert.equal(phone.at(-1)[1].path, 'Inbox.md');
  pick = { to: 3, title: 'Weekend hike', text: 'hike Mission Peak', tags: ['outdoors'] };
  r = await ideas.capture(p, 'hike mission peak this weekend'); assert.equal(phone.at(-1)[1].path, 'Journal/Weekend hike.md'); assert.match(phone.at(-1)[1].text, /# Weekend hike/);
  pick = { to: 99 }; r = await ideas.capture(p, 'something odd'); assert.equal(r.to, 'Idea Box', 'a bad answer lands in the box');
  const t = await ideas.tool(p, { action: 'recent', query: 'dentist' }); assert.equal(t.data.count, 1);
  assert.equal((await ideas.tool(p, { action: 'recent' })).data.count, 4);
  console.log('ideas ok');
}
