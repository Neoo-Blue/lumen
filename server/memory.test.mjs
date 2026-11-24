import assert from 'node:assert/strict';
const { sane } = await import(process.argv[2] || './memory.mjs');
const call = (id) => ({ role: 'assistant', content: [{ type: 'tool_use', id, name: 'x', input: {} }] });
const res = (id) => ({ role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content: 'ok' }] });
// cut in the middle: starts with a result whose call was trimmed away
let h = sane([res('a'), { role: 'assistant', content: 'hi' }, { role: 'user', content: 'q' }, call('b'), res('b'), { role: 'assistant', content: [{ type: 'text', text: 'done' }] }]);
assert.equal(h[0].role, 'user'); assert.equal(h[0].content, 'q'); assert.equal(h.length, 4);
// a turn that died after the call: the call gets a closing result
h = sane([{ role: 'user', content: 'q' }, call('c')]);
assert.equal(h.length, 3); assert.equal(h[2].content[0].tool_use_id, 'c'); assert.equal(h[2].content[0].is_error, true);
// a result for a call that is not in the previous message is dropped
h = sane([{ role: 'user', content: 'q' }, call('d'), res('zzz'), { role: 'assistant', content: 'x' }]);
assert.deepEqual(h[2].content.map(b => b.tool_use_id), ['d']);
console.log('sane ok');
