// node brain.test.mjs: the history translation keeps tool calls, results and pictures in order
import assert from 'node:assert/strict';
import { toOpenAI } from './brain.mjs';
const img = { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAAA' } };
const out = toOpenAI('be brief', [
  { role: 'user', content: 'what is on my screen?' },
  { role: 'assistant', content: [{ type: 'text', text: 'Looking.' }, { type: 'tool_use', id: 't1', name: 'phone', input: { action: 'screenshot' } }] },
  { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't1', content: [{ type: 'text', text: '{"ok":true}' }, img] }] },
]);
assert.equal(out[0].role, 'system');
assert.equal(out[2].tool_calls[0].function.arguments, '{"action":"screenshot"}');
assert.deepEqual([out[3].role, out[3].tool_call_id, out[3].content], ['tool', 't1', '{"ok":true}']);
assert.equal(out[4].content[1].image_url.url, 'data:image/png;base64,AAAA');
console.log('brain ok');
