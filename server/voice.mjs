// Voice providers for Lumen: speech to text (live captions) and text to speech, one interface for all of them.
// The page always speaks Cartesia's STT protocol to /listen: binary 16 kHz mono PCM s16le frames, the text
// messages `finalize` and `done`; it hears back {type:'transcript', text, is_final}, {type:'flush_done'}, {type:'done'}
// and {type:'error', message}. Each provider below is translated to and from that contract here, on the server,
// which holds the key: the browser never sees one.
// cfg = { provider, apiKey, sttModel, ttsModel, voice, apiSecret (LiveKit), url (optional base URL override) }
import { createHmac } from 'node:crypto';
import { WebSocket, WebSocketServer } from 'ws';

const CARTESIA_VERSION = '2026-08-14';
const LIVEKIT_URL = 'https://agent-gateway.livekit.cloud/v1';
const RATE = 16000; // what the page sends
const MAX_MS = 10 * 60e3, MAX_BYTES = 30 * 1024 * 1024, FLUSH_WAIT = 3000;

export const VOICE_PROVIDERS = {
  cartesia: { label: 'Cartesia', stt: 'stream', tts: true, needs: ['apiKey'],
    defaults: { sttModel: 'ink-whisper', ttsModel: 'sonic-3', voice: 'f786b574-daa5-4673-aa0c-cbe3e8534c02' } },
  deepgram: { label: 'Deepgram', stt: 'stream', tts: true, needs: ['apiKey'],
    defaults: { sttModel: 'nova-3', ttsModel: 'aura-2-thalia-en', voice: 'aura-2-thalia-en' } }, // a Deepgram voice is its model
  elevenlabs: { label: 'ElevenLabs', stt: 'stream', tts: true, needs: ['apiKey'],
    defaults: { sttModel: 'scribe_v2_realtime', ttsModel: 'eleven_flash_v2_5', voice: 'JBFqnCBsd6RMkjVDRZzb' } },
  openrouter: { label: 'OpenRouter', stt: 'batch', tts: true, needs: ['apiKey'],
    defaults: { sttModel: 'openai/whisper-large-v3', ttsModel: 'openai/gpt-4o-mini-tts-2025-12-15', voice: 'alloy' } },
  livekit: { label: 'LiveKit Inference', stt: 'stream', tts: true, needs: ['apiKey', 'apiSecret'], // optional: url (gateway)
    defaults: { sttModel: 'deepgram/nova-3', ttsModel: 'cartesia/sonic-3', voice: '9626c31c-bec5-4cca-baa8-f8ba9e84c8bc', url: LIVEKIT_URL } },
};

const pick = (cfg, k) => cfg[k] || VOICE_PROVIDERS[cfg.provider]?.defaults[k];
const base = (cfg, dflt) => (cfg.url || dflt).replace(/\/$/, '');
const wsBase = u => u.replace(/^http/, 'ws');
const qs = o => new URLSearchParams(Object.entries(o).filter(([, v]) => v !== undefined && v !== '')).toString();
const iso1 = lang => (lang || 'en').split(/[-_]/)[0].toLowerCase();
const b64url = s => Buffer.from(s).toString('base64url');

// LiveKit Inference takes a short-lived HS256 JWT signed with the project's API secret, carrying an inference grant.
export function livekitToken(apiKey, apiSecret, ttl = 600) {
  const now = Math.floor(Date.now() / 1000);
  const body = `${b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${b64url(JSON.stringify({ iss: apiKey, sub: 'agent', nbf: now, exp: now + ttl, inference: { perform: true } }))}`;
  return `${body}.${createHmac('sha256', apiSecret).update(body).digest('base64url')}`;
}

export function wav(pcm, rate = RATE) { // 16-bit mono PCM -> WAV
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8);
  h.write('fmt ', 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

const failed = async (who, r) => { throw new Error(`${who} ${r.status}: ${(await r.text().catch(() => '')).slice(0, 200)}`); };

// ---------- text to speech ----------
export async function tts({ text, lang = 'en', voice }, cfg) {
  const p = cfg.provider, model = pick(cfg, 'ttsModel'), v = voice || pick(cfg, 'voice');
  let r;
  if (p === 'cartesia') r = await fetch(`${base(cfg, 'https://api.cartesia.ai')}/tts/bytes`, { method: 'POST',
    headers: { 'cartesia-version': CARTESIA_VERSION, authorization: `Bearer ${cfg.apiKey}`, 'content-type': 'application/json' },
    body: JSON.stringify({ model_id: model, transcript: text, voice: { mode: 'id', id: v }, language: iso1(lang), output_format: { container: 'mp3', sample_rate: 44100, bit_rate: 128000 } }) });
  else if (p === 'deepgram') r = await fetch(`${base(cfg, 'https://api.deepgram.com')}/v1/speak?${qs({ model: voice || model, encoding: 'mp3' })}`, { method: 'POST',
    headers: { authorization: `Token ${cfg.apiKey}`, 'content-type': 'application/json' }, body: JSON.stringify({ text }) });
  else if (p === 'elevenlabs') r = await fetch(`${base(cfg, 'https://api.elevenlabs.io')}/v1/text-to-speech/${encodeURIComponent(v)}?output_format=mp3_44100_128`, { method: 'POST',
    headers: { 'xi-api-key': cfg.apiKey, 'content-type': 'application/json' },
    body: JSON.stringify({ text, model_id: model, ...(/multilingual_v2/.test(model) ? {} : { language_code: iso1(lang) }) }) }); // multilingual_v2 rejects language_code
  else if (p === 'openrouter') r = await fetch(`${base(cfg, 'https://openrouter.ai/api/v1')}/audio/speech`, { method: 'POST',
    headers: { authorization: `Bearer ${cfg.apiKey}`, 'content-type': 'application/json' },
    body: JSON.stringify({ model, input: text, voice: v, response_format: 'mp3' }) });
  else if (p === 'livekit') return { type: 'audio/wav', body: await livekitTts(text, iso1(lang), model, v, cfg) };
  else throw new Error(`unknown voice provider ${p}`);
  if (!r.ok) await failed(`${p} tts`, r);
  return { type: 'audio/mpeg', body: r.body };
}

// LiveKit Inference TTS is WebSocket only and returns 24 kHz PCM, so the answer is WAV, not MP3.
function livekitTts(text, lang, model, voice, cfg) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${wsBase(base(cfg, LIVEKIT_URL))}/tts?${qs({ model })}`, { headers: { authorization: `Bearer ${livekitToken(cfg.apiKey, cfg.apiSecret)}` } });
    const chunks = [], t = setTimeout(() => end(new Error('livekit tts timeout')), 30e3);
    const end = e => { clearTimeout(t); ws.terminate(); e ? reject(e) : resolve(wav(Buffer.concat(chunks), 24000)); };
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'session.create', model, voice, language: lang, sample_rate: '24000', encoding: 'pcm_s16le', extra: {} }));
      ws.send(JSON.stringify({ type: 'input_transcript', transcript: text + ' ' }));
      ws.send(JSON.stringify({ type: 'session.flush' }));
    });
    ws.on('message', d => { let m; try { m = JSON.parse(d); } catch { return; }
      if (m.type === 'output_audio') chunks.push(Buffer.from(m.audio, 'base64'));
      else if (m.type === 'done') end();
      else if (m.type === 'error') end(new Error(`livekit tts: ${m.message || m.code || 'error'}`)); });
    ws.on('unexpected-response', (_, res) => end(new Error(`livekit tts ${res.statusCode}`)));
    ws.on('error', end);
  });
}

// ---------- voice listing ----------
export async function voices(lang = 'en', cfg) {
  const p = cfg.provider, l = iso1(lang);
  try {
    if (p === 'cartesia') {
      const r = await fetch(`${base(cfg, 'https://api.cartesia.ai')}/voices?${qs({ language: l, limit: 40 })}`, { headers: { 'cartesia-version': CARTESIA_VERSION, authorization: `Bearer ${cfg.apiKey}` } });
      return r.ok ? ((await r.json()).data || []).map(v => ({ id: v.id, name: v.name, lang: v.language || l })) : [];
    }
    if (p === 'deepgram') {
      const r = await fetch(`${base(cfg, 'https://api.deepgram.com')}/v1/models`, { headers: { authorization: `Token ${cfg.apiKey}` } });
      return r.ok ? ((await r.json()).tts || []).filter(m => (m.languages || []).some(x => iso1(x) === l)).map(m => ({ id: m.canonical_name, name: m.name, lang: l })) : [];
    }
    if (p === 'elevenlabs') {
      const r = await fetch(`${base(cfg, 'https://api.elevenlabs.io')}/v2/voices?page_size=100`, { headers: { 'xi-api-key': cfg.apiKey } });
      if (!r.ok) return [];
      // ElevenLabs voices are multilingual: keep those verified for the language, or with no language list at all
      return ((await r.json()).voices || []).filter(v => !v.verified_languages?.length || v.verified_languages.some(x => iso1(x.language) === l))
        .map(v => ({ id: v.voice_id, name: v.name, lang: l }));
    }
  } catch { }
  return []; // openrouter, livekit: no listing API
}

// ---------- speech to text ----------
// Each streaming provider: where to connect, how to send audio and the page's two commands, and how to read its replies.
const STREAM = {
  cartesia: (cfg, lang, model) => ({
    url: `${wsBase(base(cfg, 'https://api.cartesia.ai'))}/stt/websocket?${qs({ model, language: iso1(lang), encoding: 'pcm_s16le', sample_rate: RATE, min_volume: 0.05, max_silence_duration_secs: 0.5, cartesia_version: CARTESIA_VERSION })}`,
    headers: { 'X-API-Key': cfg.apiKey },
    audio: b => b, finalize: () => 'finalize', close: () => 'close',
    map: m => m.type === 'transcript' ? [{ type: 'transcript', text: m.text || '', is_final: !!m.is_final }]
      : m.type === 'flush_done' || m.type === 'done' ? [{ type: m.type }]
      : m.type === 'error' ? [{ type: 'error', message: m.message || m.title || 'error' }] : [],
  }),
  deepgram: (cfg, lang, model) => ({
    url: `${wsBase(base(cfg, 'https://api.deepgram.com'))}/v1/listen?${qs({ model, language: lang, encoding: 'linear16', sample_rate: RATE, channels: 1, interim_results: true, punctuate: true, smart_format: true })}`,
    headers: { authorization: `Token ${cfg.apiKey}` },
    audio: b => b, finalize: () => JSON.stringify({ type: 'Finalize' }), close: () => JSON.stringify({ type: 'CloseStream' }),
    map: m => {
      if (m.type === 'Error') return [{ type: 'error', message: m.description || m.message || 'error' }];
      if (m.type !== 'Results') return [];
      const text = m.channel?.alternatives?.[0]?.transcript || '', out = text ? [{ type: 'transcript', text, is_final: !!m.is_final }] : [];
      return m.from_finalize ? [...out, { type: 'flush_done' }] : out;
    },
  }),
  elevenlabs: (cfg, lang, model) => ({ // manual commit: `finalize` commits a 10 ms silence chunk
    url: `${wsBase(base(cfg, 'https://api.elevenlabs.io'))}/v1/speech-to-text/realtime?${qs({ model_id: model, audio_format: 'pcm_16000', commit_strategy: 'manual', language_code: iso1(lang) })}`,
    headers: { 'xi-api-key': cfg.apiKey },
    audio: b => JSON.stringify({ message_type: 'input_audio_chunk', audio_base_64: b.toString('base64'), commit: false, sample_rate: RATE }),
    finalize: () => JSON.stringify({ message_type: 'input_audio_chunk', audio_base_64: Buffer.alloc(320).toString('base64'), commit: true, sample_rate: RATE }),
    close: null, // no close handshake: the bridge closes the socket and answers `done` itself
    map: m => m.message_type === 'partial_transcript' ? (m.text ? [{ type: 'transcript', text: m.text, is_final: false }] : [])
      : m.message_type === 'committed_transcript' ? [...(m.text ? [{ type: 'transcript', text: m.text, is_final: true }] : []), { type: 'flush_done' }]
      : m.error ? [{ type: 'error', message: `${m.message_type}: ${m.error}` }] : [],
  }),
  livekit: (cfg, lang, model) => ({
    url: `${wsBase(base(cfg, LIVEKIT_URL))}/stt?${qs({ model })}`,
    headers: { authorization: `Bearer ${livekitToken(cfg.apiKey, cfg.apiSecret)}` },
    open: () => JSON.stringify({ type: 'session.create', model, settings: { sample_rate: String(RATE), encoding: 'pcm_s16le', language: iso1(lang), extra: {} } }),
    audio: b => JSON.stringify({ type: 'input_audio', audio: b.toString('base64') }),
    finalize: () => JSON.stringify({ type: 'session.finalize' }), close: () => JSON.stringify({ type: 'session.close' }),
    map: m => m.type === 'interim_transcript' || m.type === 'final_transcript' ? (m.transcript ? [{ type: 'transcript', text: m.transcript, is_final: m.type === 'final_transcript' }] : [])
      : m.type === 'session.finalized' ? [{ type: 'flush_done' }] : m.type === 'session.closed' ? [{ type: 'done' }]
      : m.type === 'error' ? [{ type: 'error', message: m.message || m.code || 'error' }] : [],
  }),
};

export function listen(client, cfg, { lang = 'en' } = {}) {
  const kind = VOICE_PROVIDERS[cfg.provider]?.stt;
  const send = m => client.readyState === WebSocket.OPEN && client.send(JSON.stringify(m));
  let bytes = 0, closed = false, flushTimer, doneTimer;
  const cleanups = [];
  const close = () => { if (closed) return; closed = true; clearTimeout(cap); clearTimeout(flushTimer); clearTimeout(doneTimer); cleanups.forEach(f => f()); client.close(); };
  const fail = message => { send({ type: 'error', message }); close(); };
  const cap = setTimeout(() => fail('session time limit (10 min)'), MAX_MS);
  client.on('close', close); client.on('error', close);
  if (!kind) return fail(`speech recognition is not available for ${cfg.provider}`);
  const model = pick(cfg, 'sttModel');

  if (kind === 'batch') { // buffer until `finalize`, then one request
    const parts = [];
    let busy = false;
    client.on('message', async (d, isBinary) => {
      if (isBinary) { if ((bytes += d.length) > MAX_BYTES) return fail('session too large (30 MB)'); return parts.push(d); }
      const cmd = d.toString();
      if (cmd === 'done') { send({ type: 'done' }); return close(); }
      if (cmd !== 'finalize' || busy) return;
      busy = true;
      const pcm = Buffer.concat(parts.splice(0));
      try {
        if (pcm.length) {
          const r = await fetch(`${base(cfg, 'https://openrouter.ai/api/v1')}/audio/transcriptions`, { method: 'POST',
            headers: { authorization: `Bearer ${cfg.apiKey}`, 'content-type': 'application/json' },
            body: JSON.stringify({ model, input_audio: { data: wav(pcm).toString('base64'), format: 'wav' }, language: iso1(lang) }) });
          if (!r.ok) await failed('transcription', r);
          const text = ((await r.json()).text || '').trim();
          if (text) send({ type: 'transcript', text, is_final: true });
        }
        send({ type: 'flush_done' });
      } catch (e) { fail(e.message); }
      busy = false;
    });
    return;
  }

  const p = STREAM[cfg.provider](cfg, lang, model);
  const up = new WebSocket(p.url, { headers: p.headers });
  const queue = [];
  const toUp = m => up.readyState === WebSocket.OPEN ? up.send(m) : queue.push(m);
  let flushed = true, wantDone = false;
  const finishDone = () => { send({ type: 'done' }); close(); };
  cleanups.push(() => up.readyState === WebSocket.OPEN || up.readyState === WebSocket.CONNECTING ? up.terminate() : 0);

  up.on('open', () => { if (p.open) up.send(p.open()); queue.splice(0).forEach(m => up.send(m)); });
  up.on('unexpected-response', (_, res) => fail(`${cfg.provider} refused the connection (${res.statusCode})`));
  up.on('error', e => fail(`${cfg.provider}: ${e.message}`));
  up.on('close', (code, reason) => { if (closed) return; wantDone || code === 1000 ? finishDone() : fail(`${cfg.provider} closed (${code}${reason?.length ? ' ' + reason : ''})`); });
  up.on('message', d => {
    let m; try { m = JSON.parse(d); } catch { return; }
    for (const out of p.map(m)) {
      if (out.type === 'error') return fail(out.message);
      if (out.type === 'flush_done') { if (flushed) continue; flushed = true; clearTimeout(flushTimer); }
      if (out.type === 'done') return finishDone();
      send(out);
    }
  });

  client.on('message', (d, isBinary) => {
    if (isBinary) { if ((bytes += d.length) > MAX_BYTES) return fail('session too large (30 MB)'); return toUp(p.audio(d)); }
    const cmd = d.toString();
    if (cmd === 'finalize') { // a provider that never acknowledges must not leave the page waiting
      flushed = false; toUp(p.finalize());
      clearTimeout(flushTimer); flushTimer = setTimeout(() => { if (!flushed) { flushed = true; send({ type: 'flush_done' }); } }, FLUSH_WAIT);
    } else if (cmd === 'done') {
      wantDone = true;
      if (!p.close) return finishDone();
      toUp(p.close()); doneTimer = setTimeout(finishDone, FLUSH_WAIT);
    }
  });
}

// Attach /listen to an http(s) server. getCfg(req) -> cfg; allowed(req) -> boolean (may be async).
export function handleUpgrade(server, getCfg, allowed) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 1 << 20 });
  server.on('upgrade', async (req, sock, head) => {
    const u = new URL(req.url, 'http://x');
    // ponytail: destroys every other upgrade path too, like the handler it replaces; drop this if another path gets one
    if (u.pathname !== '/listen' || u.search.length > 600) return sock.destroy();
    sock.on('error', () => sock.destroy());
    let cfg;
    try { if (!(await allowed(req))) return sock.destroy(); cfg = await getCfg(req); } catch { return sock.destroy(); }
    wss.handleUpgrade(req, sock, head, ws => listen(ws, cfg, { lang: u.searchParams.get('language') || 'en' }));
  });
  return wss;
}
