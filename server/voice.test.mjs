// node voice.test.mjs: voice providers against local mocks (no network, no keys).
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHmac } from 'node:crypto';
import { WebSocket, WebSocketServer } from 'ws';
import { VOICE_PROVIDERS, tts, voices, wav, livekitToken, handleUpgrade } from './voice.mjs';

const listenOn = s => new Promise(r => s.listen(0, '127.0.0.1', () => r(s.address().port)));

// A mock provider: behave(ws, req) scripts its replies.
async function mockProvider(behave) {
  const http = createServer(), wss = new WebSocketServer({ server: http }), seen = { frames: [], texts: [] };
  wss.on('connection', (ws, req) => { seen.url = req.url; seen.headers = req.headers;
    ws.on('message', (d, bin) => bin ? seen.frames.push(d) : seen.texts.push(d.toString()));
    behave(ws, seen); });
  return { url: `http://127.0.0.1:${await listenOn(http)}`, seen, close: () => { wss.close(); http.close(); } };
}

// Lumen with /listen, and a page-like client that collects what it hears.
async function lumen(cfg, allowed = () => true) {
  const http = createServer(); handleUpgrade(http, () => cfg, allowed);
  const port = await listenOn(http);
  const page = new WebSocket(`ws://127.0.0.1:${port}/listen?language=en`), got = [];
  page.on('message', d => got.push(JSON.parse(d)));
  const closed = new Promise(r => { page.on('close', r); page.on('error', r); });
  return { page, got, closed, http, open: () => new Promise((r, j) => { page.on('open', r); page.on('error', j); }) };
}
const until = async (fn, ms = 2000) => { const t = Date.now(); while (!fn()) { if (Date.now() - t > ms) throw new Error('timeout'); await new Promise(r => setTimeout(r, 10)); } };
const pcm = n => Buffer.alloc(n, 1);

// (a) Deepgram streaming
{
  const dg = await mockProvider((ws, seen) => ws.on('message', (d, bin) => {
    if (bin) return ws.send(JSON.stringify({ type: 'Results', is_final: false, channel: { alternatives: [{ transcript: 'hel' }] } }));
    const m = JSON.parse(d);
    if (m.type === 'Finalize') ws.send(JSON.stringify({ type: 'Results', is_final: true, from_finalize: true, channel: { alternatives: [{ transcript: 'hello there' }] } }));
    if (m.type === 'CloseStream') { ws.send(JSON.stringify({ type: 'Metadata' })); ws.close(1000); }
  }));
  const L = await lumen({ provider: 'deepgram', apiKey: 'k1', url: dg.url });
  await L.open();
  L.page.send(pcm(320)); L.page.send(pcm(640));
  await until(() => dg.seen.frames.length === 2 && L.got.length >= 2);
  assert.deepEqual(dg.seen.frames.map(f => f.length), [320, 640], 'binary frames forwarded as is');
  assert.equal(dg.seen.headers.authorization, 'Token k1');
  assert.match(dg.seen.url, /^\/v1\/listen\?model=nova-3&language=en&encoding=linear16&sample_rate=16000&channels=1&interim_results=true/);
  assert.deepEqual(L.got[0], { type: 'transcript', text: 'hel', is_final: false });
  L.page.send('finalize');
  await until(() => L.got.some(m => m.type === 'flush_done'));
  assert.deepEqual(L.got.slice(-2), [{ type: 'transcript', text: 'hello there', is_final: true }, { type: 'flush_done' }]);
  L.page.send('done');
  await L.closed;
  assert.deepEqual(L.got.at(-1), { type: 'done' });
  assert.deepEqual(dg.seen.texts.map(t => JSON.parse(t).type), ['Finalize', 'CloseStream']);
  dg.close(); L.http.close();
  console.log('ok deepgram streaming');
}

// ElevenLabs streaming: base64 chunks, manual commit, error mapping
{
  const el = await mockProvider(ws => ws.on('message', d => {
    const m = JSON.parse(d);
    if (!m.commit) return ws.send(JSON.stringify({ message_type: 'partial_transcript', text: 'hi' }));
    ws.send(JSON.stringify({ message_type: 'committed_transcript', text: 'hi you' }));
    setTimeout(() => ws.send(JSON.stringify({ message_type: 'quota_exceeded', error: 'out of credit' })), 20);
  }));
  const L = await lumen({ provider: 'elevenlabs', apiKey: 'k2', url: el.url });
  await L.open();
  L.page.send(pcm(4)); L.page.send('finalize');
  await L.closed;
  const first = JSON.parse(el.seen.texts[0]);
  assert.equal(el.seen.headers['xi-api-key'], 'k2');
  assert.match(el.seen.url, /model_id=scribe_v2_realtime&audio_format=pcm_16000/);
  assert.deepEqual(first, { message_type: 'input_audio_chunk', audio_base_64: pcm(4).toString('base64'), commit: false, sample_rate: 16000 });
  assert.deepEqual(L.got, [{ type: 'transcript', text: 'hi', is_final: false }, { type: 'transcript', text: 'hi you', is_final: true }, { type: 'flush_done' }, { type: 'error', message: 'quota_exceeded: out of credit' }]);
  el.close(); L.http.close();
  console.log('ok elevenlabs streaming + error');
}

// LiveKit streaming: JWT, session.create, finalize/close mapping
{
  const lk = await mockProvider(ws => ws.on('message', d => {
    const m = JSON.parse(d);
    if (m.type === 'input_audio') ws.send(JSON.stringify({ type: 'interim_transcript', transcript: 'yo' }));
    if (m.type === 'session.finalize') { ws.send(JSON.stringify({ type: 'final_transcript', transcript: 'yo yo' })); ws.send(JSON.stringify({ type: 'session.finalized' })); }
    if (m.type === 'session.close') ws.send(JSON.stringify({ type: 'session.closed' }));
  }));
  const L = await lumen({ provider: 'livekit', apiKey: 'APIkey', apiSecret: 'sec', url: lk.url });
  await L.open();
  L.page.send(pcm(2)); L.page.send('finalize');
  await until(() => L.got.some(m => m.type === 'flush_done'));
  L.page.send('done');
  await L.closed;
  const [h, b, s] = lk.seen.headers.authorization.slice(7).split('.');
  assert.equal(s, createHmac('sha256', 'sec').update(`${h}.${b}`).digest('base64url'));
  assert.deepEqual((({ iss, sub, inference }) => ({ iss, sub, inference }))(JSON.parse(Buffer.from(b, 'base64url'))), { iss: 'APIkey', sub: 'agent', inference: { perform: true } });
  assert.equal(lk.seen.url, '/stt?model=deepgram%2Fnova-3');
  assert.deepEqual(JSON.parse(lk.seen.texts[0]).settings, { sample_rate: '16000', encoding: 'pcm_s16le', language: 'en', extra: {} });
  assert.deepEqual(L.got, [{ type: 'transcript', text: 'yo', is_final: false }, { type: 'transcript', text: 'yo yo', is_final: true }, { type: 'flush_done' }, { type: 'done' }]);
  lk.close(); L.http.close();
  console.log('ok livekit streaming');
}

// predicate rejects -> socket destroyed, no handshake
{
  const L = await lumen({ provider: 'deepgram', apiKey: 'x' }, () => false);
  await assert.rejects(L.open());
  L.http.close();
  console.log('ok rejected when not allowed');
}

const realFetch = globalThis.fetch;
const mockFetch = reply => { const calls = []; globalThis.fetch = async (url, init = {}) => { calls.push({ url, ...init, json: init.body ? JSON.parse(init.body) : undefined }); return reply(url, init); }; return calls; };

// (b) batch provider (OpenRouter): PCM buffered, one WAV request, one final transcript
{
  const calls = mockFetch(() => new Response(JSON.stringify({ text: ' turn on the lights ' })));
  const L = await lumen({ provider: 'openrouter', apiKey: 'or' });
  await L.open();
  L.page.send(pcm(3200)); L.page.send(pcm(3200));
  await new Promise(r => setTimeout(r, 50));
  assert.equal(calls.length, 0, 'nothing sent before finalize');
  L.page.send('finalize');
  await until(() => L.got.some(m => m.type === 'flush_done'));
  assert.deepEqual(L.got, [{ type: 'transcript', text: 'turn on the lights', is_final: true }, { type: 'flush_done' }]);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'https://openrouter.ai/api/v1/audio/transcriptions');
  assert.equal(calls[0].headers.authorization, 'Bearer or');
  assert.equal(calls[0].json.model, 'openai/whisper-large-v3');
  assert.equal(calls[0].json.input_audio.format, 'wav');
  const w = Buffer.from(calls[0].json.input_audio.data, 'base64');
  assert.equal(w.toString('ascii', 0, 4), 'RIFF'); assert.equal(w.toString('ascii', 8, 12), 'WAVE');
  assert.equal(w.readUInt32LE(4), 36 + 6400);
  assert.equal(w.readUInt16LE(20), 1, 'PCM'); assert.equal(w.readUInt16LE(22), 1, 'mono');
  assert.equal(w.readUInt32LE(24), 16000); assert.equal(w.readUInt32LE(28), 32000);
  assert.equal(w.readUInt16LE(32), 2); assert.equal(w.readUInt16LE(34), 16, '16-bit');
  assert.equal(w.toString('ascii', 36, 40), 'data'); assert.equal(w.readUInt32LE(40), 6400); assert.equal(w.length, 44 + 6400);
  L.page.send('done');
  await L.closed;
  assert.deepEqual(L.got.at(-1), { type: 'done' });
  L.http.close();
  console.log('ok openrouter batch + wav');
}

// (c) TTS request building
{
  const calls = mockFetch(() => new Response(Buffer.from('ID3fake'), { headers: { 'content-type': 'audio/mpeg' } }));
  const say = async provider => { const r = await tts({ text: 'Hi', lang: 'de-DE' }, { provider, apiKey: 'K' }); assert.equal(r.type, 'audio/mpeg'); return calls.at(-1); };

  let c = await say('cartesia');
  assert.equal(c.url, 'https://api.cartesia.ai/tts/bytes'); assert.equal(c.headers.authorization, 'Bearer K'); assert.equal(c.headers['cartesia-version'], '2026-08-14');
  assert.deepEqual(c.json, { model_id: 'sonic-3', transcript: 'Hi', voice: { mode: 'id', id: VOICE_PROVIDERS.cartesia.defaults.voice }, language: 'de', output_format: { container: 'mp3', sample_rate: 44100, bit_rate: 128000 } });

  c = await say('deepgram');
  assert.equal(c.url, 'https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=mp3'); assert.equal(c.headers.authorization, 'Token K'); assert.deepEqual(c.json, { text: 'Hi' });

  c = await say('elevenlabs');
  assert.equal(c.url, 'https://api.elevenlabs.io/v1/text-to-speech/JBFqnCBsd6RMkjVDRZzb?output_format=mp3_44100_128'); assert.equal(c.headers['xi-api-key'], 'K');
  assert.deepEqual(c.json, { text: 'Hi', model_id: 'eleven_flash_v2_5', language_code: 'de' });

  c = await say('openrouter');
  assert.equal(c.url, 'https://openrouter.ai/api/v1/audio/speech'); assert.equal(c.headers.authorization, 'Bearer K');
  assert.deepEqual(c.json, { model: 'openai/gpt-4o-mini-tts-2025-12-15', input: 'Hi', voice: 'alloy', response_format: 'mp3' });

  mockFetch(() => new Response('{"message":"bad key"}', { status: 401 }));
  await assert.rejects(tts({ text: 'Hi' }, { provider: 'openrouter', apiKey: 'K' }), /openrouter tts 401: .*bad key/);

  // LiveKit: WebSocket, PCM back, wrapped as WAV
  const lk = await mockProvider(ws => ws.on('message', d => {
    if (JSON.parse(d).type !== 'session.flush') return;
    ws.send(JSON.stringify({ type: 'output_audio', audio: pcm(100).toString('base64') }));
    ws.send(JSON.stringify({ type: 'output_audio', audio: pcm(50).toString('base64') }));
    ws.send(JSON.stringify({ type: 'done' }));
  }));
  const r = await tts({ text: 'Hi', lang: 'en' }, { provider: 'livekit', apiKey: 'a', apiSecret: 'b', url: lk.url });
  assert.equal(r.type, 'audio/wav'); assert.equal(r.body.length, 44 + 150); assert.equal(r.body.readUInt32LE(24), 24000);
  assert.deepEqual(lk.seen.texts.map(t => JSON.parse(t).type), ['session.create', 'input_transcript', 'session.flush']);
  assert.deepEqual(JSON.parse(lk.seen.texts[0]), { type: 'session.create', model: 'cartesia/sonic-3', voice: '9626c31c-bec5-4cca-baa8-f8ba9e84c8bc', language: 'en', sample_rate: '24000', encoding: 'pcm_s16le', extra: {} });
  assert.equal(lk.seen.url, '/tts?model=cartesia%2Fsonic-3');
  lk.close();
  console.log('ok tts request building (5 providers)');
}

// voices: mapping + empty for providers without a listing API
{
  mockFetch(url => new Response(JSON.stringify(
    url.includes('deepgram') ? { tts: [{ name: 'thalia', canonical_name: 'aura-2-thalia-en', languages: ['en', 'en-US'] }, { name: 'x', canonical_name: 'aura-2-x-de', languages: ['de'] }] }
      : { voices: [{ voice_id: 'v1', name: 'A', verified_languages: [{ language: 'en' }] }, { voice_id: 'v2', name: 'B', verified_languages: [{ language: 'fr' }] }] })));
  assert.deepEqual(await voices('en', { provider: 'deepgram', apiKey: 'K' }), [{ id: 'aura-2-thalia-en', name: 'thalia', lang: 'en' }]);
  assert.deepEqual(await voices('en', { provider: 'elevenlabs', apiKey: 'K' }), [{ id: 'v1', name: 'A', lang: 'en' }]);
  assert.deepEqual(await voices('en', { provider: 'openrouter', apiKey: 'K' }), []);
  console.log('ok voices');
}

assert.equal(livekitToken('a', 'b').split('.').length, 3);
assert.equal(wav(Buffer.alloc(0)).length, 44);
globalThis.fetch = realFetch;
console.log('all voice tests passed');
