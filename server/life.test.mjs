// life.mjs against a stand-in mailbox and phone: filing, notifying, watches and a routine, with no network.
import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import * as real from './mail.mjs';
import { makeLife } from './life.mjs';

const dir = await mkdtemp(tmpdir() + '/life-');
const soon = new Date(Date.now() + 3 * 864e5).toISOString().slice(0, 16) + ':00';
let round = 0;
const fake = { ...real, test: async () => true,
  poll: async (cfg, seen) => (++round === 1
    ? { seen: { uid: 10, validity: '1' }, index: [{ uid: 10, date: new Date().toISOString(), from: 'Clinic <a@clinic.example>', subject: 'Appointment confirmed', snippet: '' }],
        facts: [{ kind: 'appointment', title: 'Dentist', who: 'Clinic', when: soon, calendar: true, ref: 'D1', uid: 10, got: new Date().toISOString(), line: 'appointment Dentist' }] }
    : { seen: { uid: 11, validity: '1' }, index: [{ uid: 11, date: new Date().toISOString(), from: 'State <no-reply@state.example>', subject: 'Your passport has shipped', snippet: '' }],
        facts: [{ kind: 'payment_sent', title: 'Paid $42 to Cafe', who: 'Cafe', when: null, calendar: false, ref: null, uid: 11, got: new Date().toISOString(), line: 'payment Cafe $42' }] }) };
const notes = [], device = []; let temp = 18;
const p = { who: { uid: 'u1' }, u: { dir }, state: {}, key: 'u1' };
const life = makeLife({ mailbox: fake, vault: { seal: s => 'S' + s, unseal: s => s.slice(1) }, notify: (_, title, text) => notes.push([title, text]),
  deviceAsk: async (_, method, args) => { device.push([method, args]); return method === 'calendarEvents' ? { ok: true, data: { events: [{ title: 'Standup', start: new Date().toISOString() }] } } : method === 'skillList' ? { ok: true, data: [{ name: 'tesla climate on', params: [] }] } : { ok: true }; },
  relayLive: () => true, run: async n => n === 'weather' ? { data: { city: 'Oakland', now: { temp, condition: 'clear' }, days: [{ high: 21 }, { high: 19 }] } } : { data: { items: [{ title: 'A headline' }] } },
  nextRun: () => Date.now() + 60e3, save: () => {} });

assert.equal((await life.settings(p, { action: 'connect', addr: 'me@example.com', pass: 'abcd efgh' })).mail.addr, 'me@example.com');
await new Promise(r => setTimeout(r, 50)); // connect starts the first check in the background
assert.equal(round, 1);
assert.equal(notes.length, 0, 'the first look back is quiet');
assert.deepEqual(device.map(d => d[0]), ['calendarAdd'], 'a future booking goes into the calendar');
assert.equal(device[0][1].title, 'Dentist');
await life.tool(p, { action: 'watch', words: ['passport'], note: 'Passport news' });
await life.checkMail(p, true);
assert.ok(notes.some(n => n[0] === 'Paid $42 to Cafe'), 'a payment is told');
assert.ok(notes.some(n => n[0] === 'Passport news'), 'the watch fires');
assert.equal(p.state.mail.watches.length, 0, 'a watch fires once');
await life.checkMail(p, true); // nothing new filed twice
assert.equal(device.filter(d => d[0] === 'calendarAdd').length, 1);
const dg = await life.tool(p, { action: 'digest' });
assert.equal(dg.data.facts.length, 2);
assert.ok((await life.tool(p, { action: 'search', query: 'passport' })).data.rows[0].includes('passport'));
await life.settings(p, { action: 'routines', routines: { city: 'Oakland', morning: { on: true } } });
const tried = await life.settings(p, { action: 'try', which: 'morning' });
assert.ok(tried.tried.some(l => l.startsWith('Oakland: clear, 18°')), tried.tried.join('|'));
assert.ok(tried.tried.some(l => l.includes('Standup')));
assert.ok(notes.at(-1)[0] === 'Good morning');
// habits: two weeks of weekday departures around 8:10 in California, then the suggestion, then a plan firing
const tz = 'America/Los_Angeles'; p.state.routines.tz = tz;
const ev = []; for (let d = 1; d <= 20; d++) { const day = new Date(Date.now() - d * 864e5); if (/Sat|Sun/.test(new Intl.DateTimeFormat('en-US', { timeZone: tz, weekday: 'short' }).format(day))) continue;
  const local = new Date(day.toLocaleString('en-US', { timeZone: tz })); const off = day - local; const at = new Date(day.toLocaleDateString('en-CA', { timeZone: tz }) + 'T08:' + String(5 + d % 10).padStart(2, '0') + ':00Z'); ev.push({ kind: 'car_on', t: +at + off }); }
p.state.habits = { on: true, events: ev, found: [], foundAt: 0, asked: {}, plans: [] };
assert.equal(life.moment(p, 'car_on'), true); assert.equal(life.moment(p, 'phone_unlocked'), false, 'only the car is noted');
const before = notes.length; await life.tick(p);
const sug = notes.slice(before).find(n => /usually leave around 8:1/.test(n[0])); assert.ok(sug, JSON.stringify(notes.slice(before)));
await life.tick(p); assert.equal(notes.slice(before).filter(n => /usually leave/.test(n[0])).length, 1, 'suggested once');
const id = p.state.habits.found[0].id; assert.equal(id, 'weekdays_morning');
await life.settings(p, { action: 'habit', id, mode: 'ask', lead: 15 });
const pl = p.state.habits.plans[0]; assert.ok(pl.next > Date.now());
pl.next = Date.now() - 1; temp = 18; let n0 = notes.length; await life.tick(p); assert.equal(notes.length, n0, 'mild out: nothing today'); assert.ok(pl.next > Date.now());
pl.next = Date.now() - 1; temp = 2; n0 = notes.length; await life.tick(p); assert.ok(/Leaving around/.test(notes.at(-1)[0]) && /warm it up/.test(notes.at(-1)[1]), JSON.stringify(notes.at(-1)));
await life.settings(p, { action: 'habit', id, mode: 'auto', lead: 15 });
p.state.habits.plans[0].next = Date.now() - 1; temp = 31; await life.tick(p);
assert.deepEqual(device.at(-1), ['skillRun', { name: 'tesla climate on' }]); assert.match(notes.at(-1)[1], /Cooling it/);
life.moment(p, 'car_on', undefined, 'Galaxy Buds', 'handsfree'); life.moment(p, 'car_on', undefined, 'Tizla', 'car');
assert.ok(p.state.habits.seen.includes('Galaxy Buds') && !p.state.habits.cars.includes('Galaxy Buds'), 'earbuds are not the car');
assert.ok(p.state.habits.cars.includes('Tizla'), 'a car says so');
await life.settings(p, { action: 'car', dev: 'Galaxy Buds', car: true }); assert.ok(p.state.habits.cars.includes('Galaxy Buds'), 'unless marked');
await life.settings(p, { action: 'habits', on: false }); assert.equal(p.state.habits.events.length, 0, 'off forgets');
console.log('life ok');
