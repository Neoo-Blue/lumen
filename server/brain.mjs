// The model behind Lumen, chosen by whoever runs the server: Anthropic, or any OpenAI-compatible endpoint
// (OpenAI, Gemini through Google's OpenAI-compatible address, Groq, a local Ollama or vLLM).
// Everything in Lumen speaks one message shape (Anthropic's); this file translates for the others.
import Anthropic from '@anthropic-ai/sdk';
import { makeCliBrain } from './cli.mjs';

export const PRESETS = {
  anthropic: { label: 'Anthropic (Claude)', model: 'claude-opus-5-5' },
  openai: { label: 'OpenAI', base: 'https://api.openai.com/v1', model: 'gpt-5' },
  gemini: { label: 'Google Gemini', base: 'https://generativelanguage.googleapis.com/v1beta/openai', model: 'gemini-3-pro' },
  ollama: { label: 'Local (Ollama)', base: 'http://host.docker.internal:11434/v1', model: 'qwen3:32b' },
  custom: { label: 'Other OpenAI-compatible', base: '', model: '' },
  cli: { label: 'Your subscription (Claude, ChatGPT, Gemini…)', model: '' },
  device: { label: 'On this phone (works offline)', base: 'http://127.0.0.1:47130/v1', model: 'local' },
};

/** cfg: { provider, apiKey, baseUrl, model }. Returns { model, stream(params, onText), create(params), webSearch }. */
export function makeBrain(cfg) {
  const preset = PRESETS[cfg.provider] || PRESETS.anthropic;
  const model = cfg.model || preset.model;
  if (cfg.provider === 'cli') return makeCliBrain(cfg); // a signed-in command-line tool: see cli.mjs
  if ((cfg.provider || 'anthropic') === 'anthropic') {
    const client = new Anthropic({ apiKey: cfg.apiKey, ...(cfg.baseUrl ? { baseURL: cfg.baseUrl } : {}) });
    return {
      model, webSearch: true,
      async stream(params, onText) { const st = client.messages.stream({ ...params, model: params.model || model }); if (onText) st.on('text', onText); return st.finalMessage(); },
      create: params => client.messages.create({ ...params, model: params.model || model }),
    };
  }
  const base = (cfg.baseUrl || preset.base).replace(/\/+$/, '');
  const call = async (params, onText) => {
    if (cfg.beforeCall) await cfg.beforeCall(); // e.g. start the model on the phone
    const r = await fetch(base + '/chat/completions', {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(cfg.apiKey ? { authorization: 'Bearer ' + cfg.apiKey } : {}) },
      body: JSON.stringify({ model: cfg.provider === 'device' ? undefined : params.model || model, max_tokens: cfg.provider === 'device' ? Math.min(params.max_tokens || 2048, 2048) : params.max_tokens, stream: true, ...(cfg.provider === 'device' ? { chat_template_kwargs: { enable_thinking: false } } : {}), messages: toOpenAI(params.system, params.messages), ...(params.tools?.length ? { tools: toolsOf(params.tools) } : {}) }),
    });
    if (!r.ok) throw new Error(`model ${r.status}: ${(await r.text()).slice(0, 300)}`);
    let text = '', finish = 'stop', buf = ''; const calls = [];
    const dec = new TextDecoder();
    for await (const chunk of r.body) {
      buf += dec.decode(chunk, { stream: true });
      let i; while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
        if (!line.startsWith('data:') || line === 'data: [DONE]') continue;
        let j; try { j = JSON.parse(line.slice(5)); } catch { continue; }
        const ch = j.choices?.[0]; if (!ch) continue;
        if (ch.delta?.content) { text += ch.delta.content; onText?.(ch.delta.content); }
        for (const t of ch.delta?.tool_calls || []) {
          const at = t.index ?? (t.id || !calls.length ? calls.length : calls.length - 1); // some servers omit index on later fragments
          const c = calls[at] ||= { id: '', name: '', args: '' };
          if (t.id) c.id = t.id; if (t.function?.name) c.name += t.function.name; if (t.function?.arguments) c.args += t.function.arguments;
        }
        if (ch.finish_reason) finish = ch.finish_reason;
      }
    }
    const content = [];
    if (text) content.push({ type: 'text', text });
    calls.filter(Boolean).forEach((c, k) => { let input = {}; try { input = c.args ? JSON.parse(c.args) : {}; } catch {} content.push({ type: 'tool_use', id: c.id || `call_${k}`, name: c.name, input }); });
    return { role: 'assistant', content, stop_reason: calls.length ? 'tool_use' : finish === 'length' ? 'max_tokens' : 'end_turn' };
  };
  return { model, webSearch: false, stream: call, create: params => call(params) };
}

const toolsOf = tools => tools.filter(t => t.input_schema).map(t => ({ type: 'function', function: { name: t.name, description: t.description || '', parameters: t.input_schema } }));
const textOf = v => typeof v === 'string' ? v : Array.isArray(v) ? v.map(b => b.text || '').join('\n') : '';
const imagePart = b => ({ type: 'image_url', image_url: { url: `data:${b.source.media_type};base64,${b.source.data}` } });

/** Anthropic-shaped history to OpenAI chat messages. */
export function toOpenAI(system, messages) {
  const out = []; const sys = textOf(system); if (sys) out.push({ role: 'system', content: sys });
  for (const m of messages) {
    if (typeof m.content === 'string') { out.push({ role: m.role, content: m.content }); continue; }
    if (m.role === 'assistant') {
      const text = m.content.filter(b => b.type === 'text').map(b => b.text).join('');
      const tc = m.content.filter(b => b.type === 'tool_use').map(b => ({ id: b.id, type: 'function', function: { name: b.name, arguments: JSON.stringify(b.input || {}) } }));
      out.push({ role: 'assistant', content: text || null, ...(tc.length ? { tool_calls: tc } : {}) });
      continue;
    }
    const parts = [], pics = [];
    for (const b of m.content) {
      if (b.type === 'tool_result') { // tool messages carry text only; pictures follow as a user message
        const c = Array.isArray(b.content) ? b.content : [{ type: 'text', text: String(b.content ?? '') }];
        out.push({ role: 'tool', tool_call_id: b.tool_use_id, content: c.filter(x => x.type === 'text').map(x => x.text).join('\n') || (b.is_error ? 'error' : 'ok') });
        pics.push(...c.filter(x => x.type === 'image').map(imagePart));
      } else if (b.type === 'text') parts.push({ type: 'text', text: b.text });
      else if (b.type === 'image') parts.push(imagePart(b));
    }
    if (pics.length) out.push({ role: 'user', content: [{ type: 'text', text: '(pictures from the tool results above)' }, ...pics] });
    if (parts.length) out.push({ role: 'user', content: parts });
  }
  return out;
}
