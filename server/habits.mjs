// Habits from moments the phone notes (the phone joining or leaving the car's Bluetooth), found by script: when the
// person usually sets off in the morning and after work, on weekdays and on weekends. A habit needs enough days and a
// tight enough window to be worth acting on; the person is asked before anything is done with it.

const local = (t, tz) => { const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: tz, hour12: false, weekday: 'short', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).formatToParts(new Date(t)).map(x => [x.type, x.value]));
  return { day: `${p.year}-${p.month}-${p.day}`, wd: p.weekday, min: (+p.hour % 24) * 60 + +p.minute }; };
const hhmm = m => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
const quart = (xs, q) => { const s = [...xs].sort((a, b) => a - b), i = (s.length - 1) * q, lo = Math.floor(i); return s[lo] + (s[Math.ceil(i)] - s[lo]) * (i - lo); };

// the windows a departure is looked for in (minutes after midnight), first car connection of the day in each
export const WINDOWS = { morning: [5 * 60, 11 * 60], evening: [15 * 60, 21 * 60 + 30] };

/**
 * events: [{ kind: 'car_on' | 'car_off', t }] (ms). Returns habits like
 * { id: 'weekdays_morning', days: 'weekdays', part: 'morning', time: '08:10', n: 12, spread: 14 } (spread in minutes).
 */
export function findHabits(events, tz = 'America/Los_Angeles', now = Date.now(), { minDays = 5, maxSpread = 40, lookback = 28 } = {}) {
  const firsts = {}; // `${days}|${part}` -> { day: minute of first departure }
  for (const e of events) {
    if (e.kind !== 'car_on' || e.t < now - lookback * 864e5 || e.t > now) continue;
    const l = local(e.t, tz), days = /Sat|Sun/.test(l.wd) ? 'weekends' : 'weekdays';
    for (const [part, [a, b]] of Object.entries(WINDOWS)) if (l.min >= a && l.min <= b) {
      const k = `${days}|${part}`; (firsts[k] ||= {});
      if (firsts[k][l.day] === undefined || l.min < firsts[k][l.day]) firsts[k][l.day] = l.min;
    }
  }
  const out = [];
  for (const [k, byDay] of Object.entries(firsts)) {
    const mins = Object.values(byDay); if (mins.length < minDays) continue;
    const spread = Math.round(quart(mins, 0.75) - quart(mins, 0.25)); if (spread > maxSpread) continue;
    const [days, part] = k.split('|'), med = Math.round(quart(mins, 0.5) / 5) * 5;
    out.push({ id: `${days}_${part}`, days, part, time: hhmm(med), n: mins.length, spread });
  }
  return out.sort((a, b) => a.id.localeCompare(b.id));
}

/** The next moment a plan fires: lead minutes before the habit's time, on its kind of day. */
export function nextFire(habit, lead, tz, now = Date.now()) {
  const [h, m] = habit.time.split(':').map(Number), target = h * 60 + m - lead;
  for (let i = 0; i < 9 * 24 * 60; i += 5) {
    const t = Math.floor(now / 300e3) * 300e3 + i * 60e3; if (t <= now) continue;
    const l = local(t, tz); if (l.min !== ((target % 1440) + 1440) % 1440) continue;
    if ((habit.days === 'weekends') !== /Sat|Sun/.test(l.wd)) continue;
    return t;
  }
  return 0;
}

/** Warm, cool or leave it: from the outside temperature in °C. */
export const comfort = c => c == null || Number.isNaN(c) ? '' : c <= 12 ? 'warm' : c >= 26 ? 'cool' : '';

if (globalThis.process?.argv?.[1]?.endsWith('habits.mjs')) {
  const assert = await import('node:assert/strict');
  const tz = 'America/Los_Angeles', now = Date.parse('2026-11-24T20:00:00Z'); // a Tuesday, noon in California
  const ev = [];
  // three weeks of weekdays: in the car around 8:05 to 8:20 local (16:05Z to 16:20Z), and home time around 17:40 (01:40Z next day)
  for (let d = 1; d <= 21; d++) {
    const day = new Date(now - d * 864e5); const wd = new Intl.DateTimeFormat('en-US', { timeZone: tz, weekday: 'short' }).format(day);
    if (/Sat|Sun/.test(wd)) { ev.push({ kind: 'car_on', t: Date.parse(day.toISOString().slice(0, 10) + 'T' + (17 + (d % 5)) + ':00:00Z') }); continue; } // weekends: all over the place
    const date = day.toISOString().slice(0, 10);
    ev.push({ kind: 'car_on', t: Date.parse(`${date}T16:${String(5 + (d * 7) % 16).padStart(2, '0')}:00Z`) });
    ev.push({ kind: 'car_on', t: Date.parse(`${date}T16:45:00Z`) }); // a second trip that morning: only the first counts
    ev.push({ kind: 'car_on', t: Date.parse(`${date}T01:${String(35 + d % 10).padStart(2, '0')}:00Z`) + 864e5 });
  }
  const hs = findHabits(ev, tz, now);
  const m = hs.find(h => h.id === 'weekdays_morning'), e = hs.find(h => h.id === 'weekdays_evening');
  assert.ok(m && /^08:1[05]$|^08:05$/.test(m.time), JSON.stringify(hs));
  assert.ok(m.n >= 10 && m.spread <= 15);
  assert.ok(e && /^17:4[05]$|^17:35$/.test(e.time), JSON.stringify(hs));
  assert.ok(!hs.some(h => h.days === 'weekends'), 'scattered weekends are not a habit');
  assert.equal(findHabits(ev.slice(0, 6), tz, now).length, 0, 'too few days');
  const f = nextFire(m, 15, tz, now); const l = local(f, tz);
  assert.ok(!/Sat|Sun/.test(l.wd) && l.min === Number(m.time.slice(0, 2)) * 60 + Number(m.time.slice(3)) - 15 && f > now);
  assert.equal(comfort(3), 'warm'); assert.equal(comfort(30), 'cool'); assert.equal(comfort(18), '');
  console.log('habits ok');
}
