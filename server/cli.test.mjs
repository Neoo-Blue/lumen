// node cli.test.mjs: the conversation goes into one prompt and tool calls come back out as tool_use blocks
import assert from 'node:assert/strict';
process.env.LUMEN_DATA = '/tmp/lumen-cli-test';
const { toPrompt, parseReply, imagesOf } = await import('./cli.mjs');
const tools = [{ name: 'weather', description: 'Weather for a city', input_schema: { type: 'object', properties: { city: { type: 'string' } } } }];
const msgs = [
  { role: 'user', content: 'Weather in Oslo?' },
  { role: 'assistant', content: [{ type: 'tool_use', id: 't1', name: 'weather', input: { city: 'Oslo' } }] },
  { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't1', content: '{"temp":3}' }, { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAAA' } }] },
];
const p = toPrompt(msgs, tools);
assert.match(p, /### weather/); assert.match(p, /<tool_result id="t1">\n\{"temp":3\}/); assert.match(p, /\[Assistant\]$/);
assert.equal(imagesOf(msgs).length, 1);
let r = parseReply('Checking.\n<tool_call>{"name": "weather", "input": {"city": "Bergen"}}</tool_call>');
assert.equal(r.stop_reason, 'tool_use'); assert.deepEqual(r.content.map(b => b.type), ['text', 'tool_use']); assert.equal(r.content[1].input.city, 'Bergen');
r = parseReply('<function_calls><invoke name="weather"><parameter name="city">Paris</parameter></invoke></function_calls>');
assert.equal(r.content[0].name, 'weather'); assert.equal(r.content[0].input.city, 'Paris'); assert.equal(r.content.length, 1);
r = parseReply('It is 3 degrees in Oslo.');
assert.equal(r.stop_reason, 'end_turn'); assert.equal(r.content[0].text, 'It is 3 degrees in Oslo.');
console.log('cli ok');
