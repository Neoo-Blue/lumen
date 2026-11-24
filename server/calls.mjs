// Calls on the person's behalf: Lumen phones a business (a booking, a question, waiting on hold) with a Cartesia voice
// agent, then reports back with a summary and the transcript. Every call is confirmed by the person first, opens by
// saying it is an AI assistant calling for them and that it is recorded, and never pays or commits beyond the goal.
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

const API = 'https://api.cartesia.ai', VERSION = '2026-08-14', MODEL = 'claude-sonnet-5';

const INSTRUCTIONS = `You are Lumen, an AI assistant making a phone call on behalf of {{person}}.
Your goal on this call: {{goal}}
Details you may share: {{details}}
How to behave:
- You already said you are an AI assistant calling for {{person}} and that the call is recorded. If asked again, say so plainly.
- Stay on the goal. Be brief, warm and clear, like a capable personal assistant.
- Never pay, never give card or account numbers, never agree to costs, contracts or anything beyond the goal. If they need
  something you were not given, say you will check with {{person}} and get back to them.
- Phone menus: choose the options that reach a person or the right department.
- On hold or hearing music: stay silent and wait (skip your turn). Do not hang up while on hold.
- When the goal is done or clearly impossible, repeat the key result back (date, time, name, confirmation number, price),
  thank them, and end the call.`;

export function makeCalls(opts) {
  const { DATA } = opts; // opts.KEY and opts.PUBLIC are read live: they change in the dashboard
  const FILE = `${DATA}/calls.json`;
  let st = null;
  const load = async () => (st ||= await readFile(FILE, 'utf8').then(JSON.parse).catch(() => ({ calls: {} })));
  const save = () => writeFile(FILE, JSON.stringify(st), { mode: 0o600 }).catch(() => {});
  const cart = async (method, path, body) => {
    const r = await fetch(API + path, { method, headers: { 'X-API-Key': opts.KEY, 'Cartesia-Version': VERSION, 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(20000) });
    const text = await r.text(); let j = null; try { j = JSON.parse(text); } catch {}
    if (!r.ok) throw new Error(`calling service ${r.status}: ${(j?.message || j?.error || text).toString().slice(0, 200)}`);
    return j;
  };

  /** Agent, webhook and number, made once and remembered. The number is a paid line, so it is only used when set. */
  async function setup() {
    await load();
    if (!opts.KEY) throw new Error('calling is not set up on this server');
    st.secret ||= randomBytes(24).toString('base64url');
    if (!st.hook) {
      const old = ((await cart('GET', '/agents/webhooks?limit=100'))?.data || []).find(w => w.url === `${opts.PUBLIC}/calls/hook`);
      if (old) await cart('PATCH', `/agents/webhooks/${old.id}`, { display_name: 'Lumen calls', url: `${opts.PUBLIC}/calls/hook`, secret: st.secret });
      st.hook = old?.id || (await cart('POST', '/agents/webhooks', { display_name: 'Lumen calls', url: `${opts.PUBLIC}/calls/hook`, secret: st.secret }))?.id;
    }
    const config = {
      instructions: INSTRUCTIONS, initial_message: '{{opening}}', timezone: 'America/Los_Angeles', language: { primary: 'en-US' },
      system_tools: { end_call: { description: 'End the call once the goal is done or clearly cannot be done, after repeating the result back.', pre_tool_speech: 'force' }, skip_turn: {}, voicemail_detection: { message: '{{voicemail}}' } },
      turn: { inactivity_check_in_secs: 60, inactivity_end_call_secs: 240 }, // the service's longest waits: hold music keeps the line alive; 4 silent minutes end it
      event_webhook_id: st.hook,
    };
    if (!st.agent) {
      st.agent = (await cart('POST', '/v1/agents', { name: 'Lumen caller', description: 'Lumen calls businesses for its people: bookings, questions, waiting on hold. Made by the Lumen server.', config: { ...config, model: { id: MODEL }, audio: { input: { noise_suppression: 'auto' }, output: { voice_id: st.voice || undefined } } } }))?.id;
    } else await cart('PATCH', `/v1/agents/${st.agent}`, { config }).catch(() => {});
    st.number = process.env.LUMEN_CALL_NUMBER_ID || st.number || null;
    await save();
    return st;
  }

  /** Dial. Returns the call id, or why it could not. */
  async function place({ owner, who, profile, person, phone, business, goal, details }) {
    const s = await setup();
    if (!s.number) throw new Error('no calling line yet: the owner has to approve a phone number for Lumen first');
    const to = String(phone).replace(/[^\d+]/g, ''), us = to.startsWith('+') ? to : to.length === 10 ? '+1' + to : '+' + to;
    const opening = `Hi, this is Lumen, an AI assistant calling for ${person || 'a customer'}. This call is recorded. ${String(goal).length < 140 ? `I'm calling to ${String(goal).replace(/^to /i, '')}.` : ''}`;
    const r = await cart('POST', '/agents/calls', { agent_id: s.agent, from_number_id: s.number, ringing_timeout_seconds: 40,
      outbound_calls: [{ to_number: us, dynamic_variables: { opening, person: person || 'a customer', goal: String(goal).slice(0, 600), details: String(details || 'none').slice(0, 800), voicemail: `Hi, this is Lumen, an AI assistant calling for ${person || 'a customer'} about ${String(goal).slice(0, 120)}. We'll try again later. Thank you.` } }] });
    const id = r?.calls?.[0]?.agent_call_id;
    if (!id) throw new Error('the call was not placed: ' + JSON.stringify(r?.calls?.[0]?.error || r).slice(0, 200));
    s.calls[id] = { owner, who: { uid: who?.uid, email: who?.email || '', name: who?.name || '' }, profile, business, goal, to: us, status: 'dialing', at: Date.now() };
    await save();
    return id;
  }

  /** Call events from the calling service; returns the finished call (with its owner) when there is news for them. */
  async function hook(headers, body) {
    const s = await load();
    const got = Buffer.from(String(headers['x-webhook-secret'] || '')), want = Buffer.from(String(s.secret || '-'));
    if (got.length !== want.length || !timingSafeEqual(got, want)) return { status: 401 };
    const e = body || {}, c = s.calls[e.call_id];
    if (!c) return { status: 200 };
    const call = e.call || {};
    if (e.type === 'call_started') c.status = 'live';
    if (e.type === 'call_completed' || e.type === 'call_failed') {
      c.status = e.type === 'call_completed' ? 'done' : 'failed'; c.ended = call.end_reason || e.type;
      c.secs = call.start_time && call.end_time ? Math.round((Date.parse(call.end_time) - Date.parse(call.start_time)) / 1000) : null;
      c.transcript = (Array.isArray(e.body) ? e.body : call.transcript || []).filter(x => x?.text).map(x => ({ who: x.role === 'agent' || x.role === 'assistant' ? 'Lumen' : 'Them', text: String(x.text).slice(0, 600) })).slice(-80);
    }
    if (e.type === 'post_call_analysis') c.summary = String(e.analysis?.summary || '').slice(0, 1200);
    await save();
    const done = c; if (done.reported) return { status: 200 };
    return { status: 200, news: /completed|failed|post_call_analysis/.test(e.type) ? Object.assign(c, { id: e.call_id }) : null };
  }
  async function mark() { await save();
  }

  return { setup, place, hook, mark, get: async id => (await load()).calls[id] };
}
