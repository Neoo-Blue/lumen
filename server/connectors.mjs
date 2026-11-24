// Connectors. Each tool returns { data, ui? }: `data` goes back to the model, `ui` (components)
// is rendered on the phone immediately, without waiting for the model to write it.
// Everything is real: live public data, the person's own phone, and the services they connect.

import { S as labels } from './i18n.mjs';
import { safeFetch } from './net.mjs';

const UA = { 'user-agent': 'Lumen-prototype/0.2 (personal research prototype)' };
const getJSON = async (url, opt = {}) => {
  const r = await fetch(url, { ...opt, headers: { ...UA, ...(opt.headers || {}) }, signal: AbortSignal.timeout(12000) });
  if (!r.ok) throw new Error(`${new URL(url).host} ${r.status}`);
  return r.json();
};

const day = (offset, h, m = 0) => { const d = new Date(); d.setDate(d.getDate() + offset); d.setHours(h, m, 0, 0); return d.toISOString(); };
const loc = lang => (lang === 'zh' ? 'zh-CN' : lang === 'zh-Hant' ? 'zh-TW' : lang) || 'en';
const hm = (iso, lang) => new Date(iso).toLocaleTimeString(loc(lang), { hour: '2-digit', minute: '2-digit', hour12: false });
const md = (iso, lang) => new Date(iso).toLocaleDateString(loc(lang), { month: 'numeric', day: 'numeric', weekday: 'short' });
const isZh = lang => /^zh/.test(lang || '');

// Everything a person makes here (reminders, trips, lists, notes) starts empty and holds only their own data.
export const freshState = () => ({ reminders: [], trips: [], lists: {}, notes: [], seatPrefs: [], jobs: [], receipts: [] });
// worlds saved before the demo account was removed: keep what the person made, drop the sample data
export const cleanState = s => { const f = freshState(); for (const k of Object.keys(f)) if (s && s[k]) f[k] = s[k]; return f; };

const S = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required, additionalProperties: false });
const str = { type: 'string' };

export const tools = [
  { name: 'weather', description: 'Live weather and 5-day forecast for a city (English city name). Renders automatically.', input_schema: S({ city: str }) },
  { name: 'person', description: 'Look up a well-known person, company or thing on Wikipedia/Wikidata: photo, description, key facts. Renders a profile card automatically. Use lang "zh" for Chinese names, else "en".', input_schema: S({ name: str, lang: str }) },
  { name: 'place', description: 'Find a place, address or landmark and show it on a map. Renders a map automatically.', input_schema: S({ query: str }) },
  { name: 'route', description: 'Real directions between two places: drive, walk or bike time and distance, drawn on a map. Use it for any "how do I get to" or "how long to" question; never guess travel times. Renders automatically.', input_schema: S({ from: str, to: str, mode: { type: 'string', enum: ['driving', 'walking', 'cycling'] } }, ['from', 'to', 'mode']) },
  { name: 'quote', description: 'Live stock, ETF, index or crypto price with a 1-month chart. Symbol in Yahoo format (AAPL, ^GSPC, BTC-USD, 0700.HK). Renders automatically.', input_schema: S({ symbol: str }) },
  { name: 'news', description: 'Latest news headlines for a topic, person, company or place: title, outlet, date and a short summary, newest first. Fast (well under a second). Use it first for anything in the news; use web_search only when headlines are not enough.', input_schema: S({ query: str, lang: str }, ['query']) },
  { name: 'fx', description: 'Live currency conversion (ISO codes, e.g. USD CNY).', input_schema: S({ from: str, to: str, amount: { type: 'number' } }) },
  { name: 'flights', description: 'Live flight options and prices for a one-way trip. IATA airport codes, date YYYY-MM-DD. Renders flight cards automatically.', input_schema: S({ from: str, to: str, date: str }) },
  { name: 'reminders', description: 'List, add or complete reminders. action: list | add | done. Renders the reminder list automatically.', input_schema: S({ action: { type: 'string', enum: ['list', 'add', 'done'] }, text: str, due: str, id: { type: 'integer' } }, ['action']) },
  { name: 'trip', description: 'Create or update a TRIP that lives across conversations: city, dates, purpose, hotel, notes. Pulls the live forecast for the dates and the calendar events in range, and renders a one-screen trip dashboard automatically. Use it whenever the user plans or asks about a trip.', input_schema: S({ city: str, start: { type: 'string', description: 'YYYY-MM-DD' }, end: { type: 'string', description: 'YYYY-MM-DD' }, purpose: str, hotel: str, notes: str }, ['city', 'start', 'end']) },
  { name: 'checklist', description: 'Save an interactive checklist the user ticks by touch (packing list, to-dos for a trip). Items short. Ticks are saved without you. Renders automatically.', input_schema: S({ id: { type: 'string', description: 'stable id, e.g. "packing-boston"' }, title: str, items: { type: 'array', items: str } }, ['id', 'title', 'items']) },
  { name: 'notes', description: 'Quick notes that last: add a note, list recent ones, or search them. Renders the notes automatically.', input_schema: S({ action: { type: 'string', enum: ['add', 'list', 'search'] }, text: str }, ['action']) },
  { name: 'ride', description: 'Get a ride somewhere: shows Uber and Lyft buttons that open the app with the destination filled in. Renders automatically.', input_schema: S({ to: str }, ['to']) },
  { name: 'nearby', description: 'Find places of a kind near a point (restaurants, tacos, pharmacy, gas, coffee, parks...). Give near as an address or place, or lat/lon from the phone location. Renders a map with the places automatically.', input_schema: S({ what: str, near: str, lat: { type: 'number' }, lon: { type: 'number' } }, ['what']) },
  { name: 'audio', description: 'Live radio stations (by genre, name or country) or podcast episodes (by show name or topic), playable inside Lumen. Renders a player automatically.', input_schema: S({ kind: { type: 'string', enum: ['radio', 'podcast'] }, query: str }, ['kind', 'query']) },
  { name: 'find_connector', description: 'Find how to connect a service, app or data source the user wants (Notion, Linear, Stripe, GitHub, Canva, a bank, anything). Looks in the known catalog and the public MCP registry and renders connect cards with a one-tap Connect button automatically. Call it whenever the user wants to connect something or asks for a service you have no tool for.', input_schema: S({ query: str }) },
  { name: 'api_get', description: 'Call ANY public HTTP API or JSON endpoint with GET (no keys) when no dedicated tool or MCP server covers it: public data APIs, RSS, open government data, sports scores, transit, etc. Returns the body (JSON or text), truncated.', input_schema: S({ url: str }) },
  { name: 'web_search', description: 'Search the live web for anything current or not covered by another tool (news, prices, facts, reviews). Returns a short answer with sources.', input_schema: S({ query: str }) },
];

// ---------- live data ----------
const WMO_ZH = { 0: '晴', 1: '大致晴', 2: '多云', 3: '阴', 45: '雾', 48: '雾', 51: '小毛雨', 53: '毛雨', 55: '毛雨', 61: '小雨', 63: '中雨', 65: '大雨', 71: '小雪', 73: '中雪', 75: '大雪', 80: '阵雨', 81: '阵雨', 82: '强阵雨', 95: '雷暴', 96: '雷暴', 99: '雷暴' };
const WMO_EN = { 0: 'Clear', 1: 'Mostly clear', 2: 'Partly cloudy', 3: 'Overcast', 45: 'Fog', 48: 'Fog', 51: 'Light drizzle', 53: 'Drizzle', 55: 'Drizzle', 61: 'Light rain', 63: 'Rain', 65: 'Heavy rain', 71: 'Light snow', 73: 'Snow', 75: 'Heavy snow', 80: 'Showers', 81: 'Showers', 82: 'Heavy showers', 95: 'Thunderstorm', 96: 'Thunderstorm', 99: 'Thunderstorm' };
const wmo = (code, lang) => (isZh(lang) ? WMO_ZH : WMO_EN)[code] ?? '';
const wd = (date, lang) => new Date(date + 'T12:00').toLocaleDateString(loc(lang), { weekday: 'short' });

async function weather({ city }, lang) {
  const p = (await getJSON(`https://geocoding-api.open-meteo.com/v1/search?count=1&name=${encodeURIComponent(city)}`)).results?.[0];
  if (!p) return { data: { error: `city not found: ${city}` } };
  const w = await getJSON(`https://api.open-meteo.com/v1/forecast?latitude=${p.latitude}&longitude=${p.longitude}&current=temperature_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=7`);
  const days = w.daily.time.map((t, i) => ({ date: t, day: wd(t, lang), high: w.daily.temperature_2m_max[i], low: w.daily.temperature_2m_min[i], rain: w.daily.precipitation_probability_max[i], condition: wmo(w.daily.weather_code[i], lang) }));
  const data = { city: p.name, country: p.country, now: { temp: w.current.temperature_2m, condition: wmo(w.current.weather_code, lang), wind_kmh: w.current.wind_speed_10m }, days };
  return { data, ui: [{ c: 'weather', city: p.name, temp: data.now.temp, condition: data.now.condition, high: days[0].high, low: days[0].low, days: days.slice(0, 6) }] };
}

async function person({ name, lang }, ui) {
  const L = labels(ui);
  lang = lang === 'zh' ? 'zh' : 'en';
  const wiki = `https://${lang}.wikipedia.org`;
  const hit = (await getJSON(`${wiki}/w/api.php?action=query&list=search&srlimit=1&format=json&srsearch=${encodeURIComponent(name)}`)).query.search[0];
  if (!hit) return { data: { error: `not found: ${name}` } };
  // refuse a result that is not what was asked for (search can drift to a loosely related article)
  const want = name.toLowerCase().split(/\s+/).filter(w => w.length > 2), title = hit.title.toLowerCase();
  if (lang !== 'zh' && want.length && !want.some(w => title.includes(w))) return { data: { error: `no page found for ${name}` } };
  if (lang === 'zh' && ![...name].some(ch => /[\u4e00-\u9fff]/.test(ch) && (hit.title.includes(ch) || hit.snippet?.includes(ch)))) return { data: { error: `no page found for ${name}` } };
  const s = await getJSON(`${wiki}/api/rest_v1/page/summary/${encodeURIComponent(hit.title)}`, { headers: { 'accept-language': lang === 'zh' ? 'zh-cn' : 'en' } });
  const facts = [];
  if (s.wikibase_item) {
    try {
      const e = (await getJSON(`https://www.wikidata.org/wiki/Special:EntityData/${s.wikibase_item}.json`)).entities[s.wikibase_item];
      const claim = p => e.claims?.[p]?.map(c => c.mainsnak?.datavalue?.value).filter(Boolean) || [];
      const ids = p => claim(p).map(v => v.id).filter(Boolean).slice(0, 3);
      const want = { P106: L.occupation, P27: L.citizenship, P19: L.birthplace, P108: L.employer, P69: L.education, P26: L.spouse, P159: L.hq, P112: L.founders };
      const idSet = [...new Set(Object.keys(want).flatMap(ids))];
      const labels = idSet.length ? (await getJSON(`https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels&languages=${lang === 'zh' ? 'zh-cn|zh' : 'en'}|en&ids=${idSet.join('|')}`)).entities : {};
      const label = id => labels[id]?.labels?.['zh-cn']?.value || labels[id]?.labels?.[lang]?.value || labels[id]?.labels?.en?.value;
      const born = claim('P569')[0]?.time, died = claim('P570')[0]?.time, inception = claim('P571')[0]?.time;
      const yr = t => t && t.slice(1, 11).replace(/-00/g, '');
      if (born) facts.push({ label: L.born, value: yr(born) });
      if (died) facts.push({ label: L.died, value: yr(died) });
      if (inception) facts.push({ label: L.founded, value: yr(inception) });
      for (const [p, l] of Object.entries(want)) { const v = ids(p).map(label).filter(Boolean); if (v.length) facts.push({ label: l, value: v.join(lang === 'zh' ? '、' : ', ') }); }
      const site = claim('P856')[0]; if (site) facts.push({ label: L.website, value: site.replace(/^https?:\/\/(www\.)?/, '').replace(/\/$/, '') });
    } catch {}
  }
  const card = { c: 'person', name: (s.displaytitle || s.title).replace(/<[^>]+>/g, ''), description: s.description || '', photo: s.originalimage?.source || s.thumbnail?.source || '', summary: (s.extract || '').slice(0, 420), facts: facts.slice(0, 8), url: s.content_urls?.desktop?.page || '' };
  return { data: { name: card.name, description: card.description, summary: s.extract, facts }, ui: [card] };
}

async function place({ query }) {
  const r = (await getJSON(`https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=${encodeURIComponent(query)}`))[0];
  if (!r) return { data: { error: `not found: ${query}` } };
  const data = { name: r.name || query, address: r.display_name, lat: +r.lat, lon: +r.lon, kind: r.type };
  return { data, ui: [{ c: 'map', ...data }] };
}

async function geocode(q) {
  const r = (await getJSON(`https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=${encodeURIComponent(q)}`))[0];
  const words = q.toLowerCase().split(/[\s,]+/).filter(w => w.length > 3 && !/^(drive|street|road|avenue|blvd|lane|court|place|california|city)$/.test(w) && !/^\d+$/.test(w));
  const fits = got => !words.length || got.toLowerCase().includes(words[0]); // refuse a confident wrong answer
  if (r && fits(`${r.name} ${r.display_name}`)) return { name: r.name || q, address: r.display_name, lat: +r.lat, lon: +r.lon };
  // fallback: a more forgiving geocoder for house addresses and partial names
  const f = (await getJSON(`https://photon.komoot.io/api/?limit=1&q=${encodeURIComponent(q)}`)).features?.[0];
  if (!f) return null;
  const pr = f.properties;
  if (!fits([pr.name, pr.street, pr.city].filter(Boolean).join(' '))) return null;
  return { name: pr.name || [pr.housenumber, pr.street].filter(Boolean).join(' ') || q, address: [pr.housenumber, pr.street, pr.city, pr.state].filter(Boolean).join(', '), lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0] };
}
async function route({ from, to, mode = 'driving' }, here) {
  const atHere = /^(here|current location|my location|me)$/i.test(String(from).trim());
  if (atHere && !here) return { data: { error: 'phone location not shared; start from the saved home address instead' } };
  const [a, b] = await Promise.all([atHere ? { name: 'here', lat: here.lat, lon: here.lon, here: true } : geocode(from), geocode(to)]);
  if (!a || !b) return { data: { error: `could not find ${!a ? from : to}` } };
  const prof = { driving: 'driving', walking: 'foot', cycling: 'bike' }[mode] || 'driving';
  const j = await getJSON(`https://router.project-osrm.org/route/v1/${prof}/${a.lon},${a.lat};${b.lon},${b.lat}?overview=full&geometries=geojson&steps=false`);
  const rt = j.routes?.[0];
  if (!rt) return { data: { error: 'no route found' } };
  // the public router is tuned for cars; derive walking and cycling from distance when it returns car timings
  const km = rt.distance / 1000, min = mode === 'walking' ? km / 4.8 * 60 : mode === 'cycling' ? km / 16 * 60 : rt.duration / 60;
  const coords = rt.geometry.coordinates, step = Math.max(1, Math.ceil(coords.length / 180));
  const data = { from: a, to: b, mode, minutes: Math.round(min), km: +km.toFixed(1), miles: +(km * 0.621371).toFixed(1), note: mode === 'driving' ? 'typical time without live traffic' : undefined };
  return { data, ui: [{ c: 'route', ...data, path: coords.filter((_, i) => i % step === 0 || i === coords.length - 1).map(([x, y]) => [+y.toFixed(5), +x.toFixed(5)]) }] };
}

async function quote({ symbol }) {
  const j = await getJSON(`https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=1mo&interval=1d`);
  const r = j.chart.result?.[0];
  if (!r) return { data: { error: `no quote for ${symbol}` } };
  const m = r.meta, closes = r.indicators.quote[0].close.filter(v => v != null);
  const price = m.regularMarketPrice, prev = closes.length > 1 ? closes[closes.length - 2] : m.chartPreviousClose;
  const data = { symbol: m.symbol, name: m.longName || m.shortName || m.symbol, price, currency: m.currency, change_pct: +((price / prev - 1) * 100).toFixed(2), month_change_pct: +((price / closes[0] - 1) * 100).toFixed(2), high_1mo: Math.max(...closes), low_1mo: Math.min(...closes), exchange: m.fullExchangeName };
  return { data, ui: [{ c: 'quote', ...data, series: closes.map(v => +v.toFixed(2)) }] };
}

async function fx({ from, to, amount }) {
  from = String(from).toUpperCase(); to = String(to).toUpperCase();
  if (!/^[A-Z]{3}$/.test(from) || !/^[A-Z]{3}$/.test(to) || !Number.isFinite(+amount)) return { data: { error: 'use three-letter currency codes and a number' } };
  const j = await getJSON(`https://api.frankfurter.dev/v1/latest?base=${from}&symbols=${to}`);
  const rate = j.rates[to];
  return { data: { from, to, amount, rate, result: +(amount * rate).toFixed(2), date: j.date } };
}

// ponytail: prices come from a live web search, not a fare API; swap in a Google Flights scraper
// (fast-flights) or SerpApi when one is allowed here.
// Live Google Flights when the reader is installed (deploy/install-flights.sh), else a web search.
const FLIGHTS_PY = process.env.LUMEN_FLIGHTS_PY || 'python3';
async function liveFlights(from, to, date) {
  const { existsSync } = await import('node:fs');
  if (!existsSync(FLIGHTS_PY) || !/^[A-Z]{3}$/i.test(from) || !/^[A-Z]{3}$/i.test(to) || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return null;
  const { execFile } = await import('node:child_process');
  const out = await new Promise(res => execFile(FLIGHTS_PY, [new URL('./flights.py', import.meta.url).pathname, from, to, date], { timeout: 25000, maxBuffer: 1e6 }, (e, so) => res(e ? null : so)));
  try {
    const j = JSON.parse(out);
    const fl = (j.flights || []).filter(f => f.price).sort((a, b) => a.price - b.price).map(f => ({
      airline: f.airline, flight: f.flight, depart: f.depart, arrive: f.arrive, stops: f.stops, via: f.via, price: f.price,
      plus_days: f.arrive_date && f.depart_date && f.arrive_date > f.depart_date ? Math.round((Date.parse(f.arrive_date) - Date.parse(f.depart_date)) / 864e5) : 0,
      duration: f.minutes ? `${Math.floor(f.minutes / 60)}h ${f.minutes % 60}m` : null }));
    return fl.length ? { currency: 'USD', flights: fl, source: 'live fares' } : null;
  } catch { return null; }
}

async function flights({ from, to, date }) {
  const live = await liveFlights(from, to, date);
  if (live) return { data: { ...live, flights: live.flights.slice(0, 8) }, ui: [{ c: 'flights', from, to, date, flights: live.flights.slice(0, 6), currency: 'USD' }] };
  const r = await webSearch(`One-way economy flights ${from} to ${to} on ${date}: current prices from Google Flights, Kayak or airline sites.`,
    'Search the web, then reply with ONLY a JSON object, no prose: {"currency":"USD","flights":[{"airline":"...","depart":"HH:MM","arrive":"HH:MM","plus_days":0,"duration":"5h 35m","stops":0,"price":123}]}. Up to 6 real options you found for that exact date, cheapest first. Use null for anything you could not find. Never invent a flight.');
  let data;
  try { data = JSON.parse(r.answer.slice(r.answer.indexOf('{'), r.answer.lastIndexOf('}') + 1)); } catch { return { data: { error: 'no structured fares found', raw: r.answer, sources: r.sources } }; }
  data.sources = r.sources; data.note = 'prices from a live web search; final price on the booking page';
  if (!data.flights?.length) return { data };
  return { data: { ...data, flights: data.flights.slice(0, 8) }, ui: [{ c: 'flights', from, to, date, flights: data.flights.slice(0, 6), currency: data.currency }] };
}

export { isPublicHost } from './net.mjs';

// Public internet only: refuse loopback, private and link-local hosts so the agent cannot reach the home network.
async function apiGet(url) {
  const u = new URL(url);
  if (!/^https?:$/.test(u.protocol)) return { error: 'http(s) only' };
  const r = await safeFetch(u, { headers: { ...UA, accept: 'application/json, text/plain, */*' }, signal: AbortSignal.timeout(12000) }, { allowHttp: true }).catch(e => ({ error: e.message }));
  if (r.error) return { error: /private/.test(r.error) ? 'private address refused' : r.error };
  const text = await r.text();
  return { status: r.status, content_type: r.headers.get('content-type'), body: text.slice(0, 10000) };
}

// Well-known remote MCP servers (auth "oauth" = one-tap sign-in in a popup, "none" = connects instantly).
// ponytail: hand-kept list; the public registry covers the long tail.
const DOES_EN = { notion: 'Read and write your Notion pages and databases', linear: 'See and create issues and projects', atlassian: 'Tickets, docs and projects', asana: 'Tasks and projects', stripe: 'Revenue, customers, invoices', paypal: 'Transactions and invoices', square: 'Store sales and inventory', sentry: 'App errors and performance', canva: 'Designs and assets', intercom: 'Support conversations', monday: 'Boards and workflows', box: 'Cloud files', vercel: 'Website deployments', supabase: 'Databases', webflow: 'Website content', wix: 'Websites and stores', huggingface: 'Models, datasets, spaces', deepwiki: 'Explains any GitHub repository', context7: 'Up-to-date developer docs', 'cloudflare-docs': 'Search the Cloudflare docs' };
const CATALOG = [
  ['notion', 'Notion', 'https://mcp.notion.com/mcp', 'oauth', '读写你的 Notion 页面和数据库', 'notion.so'],
  ['linear', 'Linear', 'https://mcp.linear.app/mcp', 'oauth', '查看和创建任务、项目', 'linear.app'],
  ['atlassian', 'Jira 和 Confluence', 'https://mcp.atlassian.com/v1/sse', 'oauth', '工单、文档、项目', 'atlassian.com'],
  ['asana', 'Asana', 'https://mcp.asana.com/sse', 'oauth', '任务和项目', 'asana.com'],
  ['stripe', 'Stripe', 'https://mcp.stripe.com', 'oauth', '收入、客户、发票', 'stripe.com'],
  ['paypal', 'PayPal', 'https://mcp.paypal.com/sse', 'oauth', '交易、发票', 'paypal.com'],
  ['square', 'Square', 'https://mcp.squareup.com/sse', 'oauth', '门店销售、库存', 'squareup.com'],
  ['sentry', 'Sentry', 'https://mcp.sentry.dev/mcp', 'oauth', '应用报错和性能', 'sentry.io'],
  ['canva', 'Canva', 'https://mcp.canva.com/mcp', 'oauth', '设计和素材', 'canva.com'],
  ['intercom', 'Intercom', 'https://mcp.intercom.com/mcp', 'oauth', '客服对话', 'intercom.com'],
  ['monday', 'monday.com', 'https://mcp.monday.com/sse', 'oauth', '看板和工作流', 'monday.com'],
  ['box', 'Box', 'https://mcp.box.com', 'oauth', '云端文件', 'box.com'],
  ['vercel', 'Vercel', 'https://mcp.vercel.com', 'oauth', '网站部署', 'vercel.com'],
  ['supabase', 'Supabase', 'https://mcp.supabase.com/mcp', 'oauth', '数据库', 'supabase.com'],
  ['webflow', 'Webflow', 'https://mcp.webflow.com/sse', 'oauth', '网站内容', 'webflow.com'],
  ['wix', 'Wix', 'https://mcp.wix.com/sse', 'oauth', '网站和商店', 'wix.com'],
  ['huggingface', 'Hugging Face', 'https://huggingface.co/mcp', 'none', '模型、数据集、空间', 'huggingface.co'],
  ['deepwiki', 'DeepWiki', 'https://mcp.deepwiki.com/mcp', 'none', '解释任何 GitHub 仓库', 'deepwiki.com'],
  ['context7', 'Context7', 'https://mcp.context7.com/mcp', 'none', '最新的开发文档', 'context7.com'],
  ['cloudflare-docs', 'Cloudflare 文档', 'https://docs.mcp.cloudflare.com/mcp', 'none', 'Cloudflare 文档搜索', 'cloudflare.com'],
].map(([id, name, url, auth, does, site]) => ({ id, name, url, auth, does, does_en: DOES_EN[id], icon: `https://icons.duckduckgo.com/ip3/${site}.ico` }));

async function findConnector(query) {
  const q = query.toLowerCase().trim();
  const hits = CATALOG.filter(c => q.includes(c.id) || c.name.toLowerCase().includes(q) || q.includes(c.name.toLowerCase()));
  if (hits.length) return hits.slice(0, 4);
  try {
    const j = await getJSON(`https://registry.modelcontextprotocol.io/v0/servers?limit=20&search=${encodeURIComponent(q)}`);
    const out = [];
    for (const x of (j.servers || []).map(s => s.server || s)) {
      const r = (x.remotes || []).find(r => /streamable-http|sse/.test(r.type) && r.url && !/\{/.test(r.url) && !(r.headers || []).some(h => h.isRequired));
      if (!r || out.some(o => o.url === r.url)) continue;
      out.push({ id: x.name.split('/').pop().replace(/[^a-z0-9-]/gi, '-').slice(0, 20), name: x.title || x.name.split('/').pop(), url: r.url, auth: 'auto', does: (x.description || '').slice(0, 80), icon: x.icons?.[0]?.src || `https://icons.duckduckgo.com/ip3/${new URL(r.url).hostname}.ico`, transport: r.type === 'sse' ? 'sse' : undefined });
      if (out.length >= 4) break;
    }
    return out;
  } catch { return []; }
}

// web_search is wired by the server (it needs the model client).
let webSearch = async () => ({ error: 'web search unavailable' });
export const setWebSearch = fn => { webSearch = fn; };

// ---------- trips, seats, boarding passes, reminders, brokerage ----------
function tripCard(state, t, lang) {
    const lists = Object.values(state.lists).filter(l => l.trip === t.id);
  const items = lists.flatMap(l => l.items), done = items.filter(i => i.done).length;
  const rem = state.reminders.filter(r => r.trip === t.id && !r.done).length;
  return { c: 'trip', id: t.id, city: t.city, start: t.start, end: t.end, purpose: t.purpose, hotel: t.hotel, days: t.days,
    flights: [],
    packing: items.length ? { done, total: items.length } : null, reminders: rem };
}
async function tripPlan(state, i, lang) {
  let t = state.trips.find(x => x.city.toLowerCase() === i.city.toLowerCase() && x.start === i.start);
  if (!t) { t = { id: 'trip-' + (state.trips.length + 1), created: new Date().toISOString() }; state.trips.push(t); }
  Object.assign(t, { city: i.city, start: i.start, end: i.end, purpose: i.purpose || t.purpose, hotel: i.hotel || t.hotel, notes: i.notes || t.notes });
  try { // live forecast for the trip days that are within reach
    const w = (await weather({ city: i.city }, lang)).data;
    t.days = (w.days || []).filter(d => d.date >= i.start && d.date <= i.end).map(d => ({ day: d.day, high: Math.round(d.high), low: Math.round(d.low), rain: d.rain, condition: d.condition }));
    t.forecastNote = t.days.length ? '' : 'trip is beyond the 6-day forecast';
  } catch {}
  const data = { trip: t };
  return { data, ui: [tripCard(state, t, lang)] };
}

// ---------- places nearby (OpenStreetMap via Photon), radio (radio-browser), podcasts (iTunes + RSS) ----------
const km = (a, b, c, d) => { const r = x => x * Math.PI / 180, R = 6371; const h = Math.sin(r(c - a) / 2) ** 2 + Math.cos(r(a)) * Math.cos(r(c)) * Math.sin(r(d - b) / 2) ** 2; return 2 * R * Math.asin(Math.sqrt(h)); };
async function nearby({ what, near, lat, lon }) {
  if (!(Number.isFinite(lat) && Number.isFinite(lon))) { const g = near ? await geocode(near) : null; if (!g) return { data: { error: 'need a place or the phone location' } }; lat = g.lat; lon = g.lon; }
  const j = await getJSON(`https://photon.komoot.io/api/?limit=12&lat=${lat}&lon=${lon}&q=${encodeURIComponent(what)}`);
  const places = (j.features || []).map(f => { const p = f.properties, [x, y] = f.geometry.coordinates; return { name: p.name, kind: (p.osm_value || '').replace(/_/g, ' '), address: [p.housenumber, p.street, p.city].filter(Boolean).join(' '), lat: y, lon: x, km: +km(lat, lon, y, x).toFixed(2) }; })
    .filter(p => p.name && p.km < 25).sort((a, b) => a.km - b.km).slice(0, 6);
  if (!places.length) return { data: { error: `nothing found for ${what} nearby` } };
  return { data: { center: { lat, lon }, places }, ui: [{ c: 'places', center: { lat, lon }, places }] };
}
async function audio({ kind, query }) {
  if (kind === 'radio') {
    const j = await getJSON(`https://de1.api.radio-browser.info/json/stations/search?limit=12&order=clickcount&reverse=true&hidebroken=true&${/^[a-z ]+$/i.test(query) && query.split(' ').length <= 2 ? 'tag' : 'name'}=${encodeURIComponent(query.toLowerCase())}`);
    const items = j.filter(x => /^https:/.test(x.url_resolved || '')).slice(0, 5).map(x => ({ title: x.name.trim(), subtitle: [x.country, (x.tags || '').split(',').slice(0, 2).join(', ')].filter(Boolean).join(' · '), src: x.url_resolved, art: /^https:/.test(x.favicon || '') ? x.favicon : '' }));
    return items.length ? { data: items.map(({ title, subtitle }) => ({ title, subtitle })), ui: [{ c: 'audio', kind, items }] } : { data: { error: 'no stations found' } };
  }
  const j = await getJSON(`https://itunes.apple.com/search?media=podcast&limit=3&term=${encodeURIComponent(query)}`);
  const show = (j.results || [])[0];
  if (!show?.feedUrl) return { data: { error: 'no podcast found' } };
  const r = await safeFetch(show.feedUrl, { headers: UA, signal: AbortSignal.timeout(12000) });
  const xml = (await r.text()).slice(0, 400000);
  const items = [...xml.matchAll(/<item>([\s\S]*?)<\/item>/g)].slice(0, 5).map(m => {
    const it = m[1], tag = n => (it.match(new RegExp(`<${n}[^>]*>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?</${n}>`)) || [])[1] || '';
    const src = (it.match(/<enclosure[^>]*url="([^"]+)"/) || [])[1] || '';
    return { title: tag('title').replace(/<[^>]+>/g, '').trim().slice(0, 120), subtitle: (tag('pubDate') || '').slice(0, 16), src: src.replace(/&amp;/g, '&'), art: show.artworkUrl100 || '' };
  }).filter(x => /^https:/.test(x.src));
  return items.length ? { data: { show: show.collectionName, episodes: items.map(({ title, subtitle }) => ({ title, subtitle })) }, ui: [{ c: 'audio', kind, show: show.collectionName, items }] } : { data: { error: 'no playable episodes' } };
}

// ---------- short cache for live lookups ----------
const memo = new Map();
const cached = (ttl, fn) => async (input, ...rest) => {
  const k = fn.name + JSON.stringify(input) + JSON.stringify(rest);
  const hit = memo.get(k);
  if (hit && Date.now() - hit.t < ttl) return structuredClone(hit.v);
  const v = await fn(input, ...rest);
  if (!v?.data?.error) { memo.set(k, { t: Date.now(), v: structuredClone(v) }); if (memo.size > 500) memo.delete(memo.keys().next().value); }
  return v;
};
const weatherC = cached(10 * 60e3, weather), personC = cached(60 * 60e3, person), quoteC = cached(30e3, quote), fxC = cached(10 * 60e3, fx), routeC = cached(5 * 60e3, route), placeC = cached(60 * 60e3, place);

// ---------- the home screen: what matters now, built without the model ----------
export async function home(state, lang) {
  const L = labels(lang), now = Date.now(), today = new Date(); today.setHours(0, 0, 0, 0);
  const cards = [];
  const trip = state.trips.filter(t => Date.parse(t.end + 'T23:59') >= now).sort((a, b) => a.start.localeCompare(b.start))[0];
  if (trip) cards.push(tripCard(state, trip, lang));
  const due = state.reminders.filter(r => !r.done).slice(0, 4);
  if (due.length) cards.push({ c: 'checklist', id: 'reminders', title: L.reminders || 'Reminders', items: due.map(r => ({ text: r.text + (r.due ? ' · ' + r.due : ''), done: false, rid: r.id })) });
  return cards.slice(0, 3);
}

// ---------- dispatcher ----------
export async function run(name, input, { state, lang = 'en', here = null } = { state: freshState() }) {
  const L = labels(lang);
  switch (name) {
    case 'weather': return weatherC(input, lang);
    case 'person': return personC(input, lang);
    case 'place': return placeC(input);
    case 'route': return /^(here|current location|my location|me)$/i.test(String(input.from).trim()) ? route(input, here) : routeC(input);
    case 'quote': {
      const r = await quoteC(input);
      const h = r.data?.price && state.portfolio?.holdings.find(x => x.symbol === r.data.symbol);
      if (h) { const pos = { qty: h.qty, avg: h.avg, value: +(r.data.price * h.qty).toFixed(2), gain: +((r.data.price - h.avg) * h.qty).toFixed(2), gain_pct: +((r.data.price / h.avg - 1) * 100).toFixed(1) };
        r.data.your_position = pos; r.ui[0].position = pos; }
      return r;
    }
    case 'news': return news(input);
    case 'fx': return fxC(input);
    case 'flights': return flights(input);
    case 'reminders': {
      if (input.action === 'add' && input.text) state.reminders.push({ id: state.reminders.length + 1, text: String(input.text).slice(0, 140), due: input.due || '', done: false, trip: state.trips.at(-1)?.id });
      if (input.action === 'done') { const r = state.reminders.find(x => x.id === input.id); if (r) r.done = true; }
      const open = state.reminders.filter(r => !r.done);
      return { data: state.reminders, ui: [{ c: 'checklist', id: 'reminders', title: L.reminders || 'Reminders', items: state.reminders.slice(-8).map(r => ({ text: r.text + (r.due ? ' · ' + (Date.parse(r.due) ? `${md(r.due, lang)} ${hm(r.due, lang)}` : r.due) : ''), done: r.done, rid: r.id })) }].filter(() => open.length || input.action !== 'list' || state.reminders.length) };
    }
    case 'trip': return tripPlan(state, input, lang);
    case 'checklist': {
      const items = (input.items || []).slice(0, 24).map(t => ({ text: String(t).slice(0, 80), done: false }));
      const prev = state.lists[input.id];
      if (prev) for (const it of items) it.done = !!prev.items.find(p => p.text === it.text)?.done;
      state.lists[input.id] = { id: input.id, title: input.title, items, trip: state.trips.at(-1)?.id };
      return { data: { saved: input.id, items: items.length }, ui: [{ c: 'checklist', id: input.id, title: input.title, items }] };
    }
    case 'nearby': return nearby(input);
    case 'audio': return audio(input);
    case 'notes': {
      state.notes ??= [];
      if (input.action === 'add' && input.text) state.notes.push({ id: state.notes.length + 1, text: String(input.text).slice(0, 500), at: new Date().toISOString() });
      const q = String(input.text || '').toLowerCase().split(/\s+/).filter(w => w.length > 1);
      const hits = input.action === 'search' ? state.notes.filter(n => q.some(w => n.text.toLowerCase().includes(w))) : state.notes.slice(-6);
      return { data: hits, ui: hits.length ? [{ c: 'list', title: L.notes || 'Notes', items: hits.slice(-6).reverse().map(n => ({ title: n.text.slice(0, 90), meta: md(n.at, lang), icon: '✎' })) }] : undefined };
    }
    case 'ride': {
      const g = await geocode(input.to);
      if (!g) return { data: { error: `could not find ${input.to}` } };
      const uber = `https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[latitude]=${g.lat}&dropoff[longitude]=${g.lon}&dropoff[formatted_address]=${encodeURIComponent(g.address)}`;
      const lyft = `https://lyft.com/ride?id=lyft&destination[latitude]=${g.lat}&destination[longitude]=${g.lon}`;
      return { data: { to: g.name, address: g.address }, ui: [{ c: 'ride', to: g.name, address: g.address, uber, lyft }] };
    }
    case 'find_connector': {
      const found = (await findConnector(input.query)).map(f => ({ ...f, does: isZh(lang) ? f.does : f.does_en || f.does }));
      return { data: found.length ? found.map(f => ({ name: f.name, auth: f.auth, url: f.url })) : { none: true, hint: 'no connectable MCP found; suggest api_get or web_search' }, ui: found.length ? [{ c: 'connect', items: found }] : undefined };
    }
    case 'api_get': return { data: await apiGet(input.url) };
    case 'web_search': {
      const r = await webSearch(input.query);
      return { data: r }; // sources stay with the model; the screen shows the answer, not a row of site names
    }
    default: return { data: { error: `unknown tool ${name}` } };
  }
}

// node connectors.mjs -> live smoke test of the keyless connectors
if (import.meta.url === `file://${process.argv[1]}`) {
  for (const [n, i] of [['weather', { city: 'Tokyo' }], ['person', { name: 'Jensen Huang', lang: 'en' }], ['person', { name: '周杰伦', lang: 'zh' }], ['place', { query: 'Golden Gate Bridge' }], ['quote', { symbol: 'NVDA' }], ['fx', { from: 'USD', to: 'CNY', amount: 100 }]]) {
    const t = Date.now();
    try { const r = await run(n, i); console.log(n, Date.now() - t + 'ms', JSON.stringify(r.ui?.[0] || r.data).slice(0, 220)); }
    catch (e) { console.log(n, 'FAIL', e.message); }
  }
}


/** Headlines from Google News' public RSS feed: no key, a fraction of a second. */
async function news({ query, lang = 'en' }) {
  const q = String(query || '').trim().slice(0, 200);
  if (!q) return { data: { error: 'no topic' } };
  const zh = /^zh/.test(lang) || /[\u4e00-\u9fff]/.test(q);
  const url = `https://news.google.com/rss/search?q=${encodeURIComponent(q + ' when:30d')}&${zh ? 'hl=zh-CN&gl=CN&ceid=CN:zh-Hans' : 'hl=en-US&gl=US&ceid=US:en'}`;
  const r = await fetch(url, { signal: AbortSignal.timeout(6000), headers: { 'user-agent': 'Mozilla/5.0 Lumen' } }).catch(() => null);
  if (!r?.ok) return { data: { error: 'news unavailable' } };
  const xml = await r.text();
  const tag = (item, t) => (item.match(new RegExp(`<${t}[^>]*>([\\s\\S]*?)</${t}>`)) || [])[1] || '';
  const text = v => v.replace(/<!\[CDATA\[|\]\]>/g, '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/\s+/g, ' ').trim();
  const items = [...xml.matchAll(/<item>([\s\S]*?)<\/item>/g)].slice(0, 8).map(([, it]) => {
    const source = text(tag(it, 'source')), title = text(tag(it, 'title')).replace(new RegExp(`\\s+-\\s+${source.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`), '');
    return { title, source, date: new Date(text(tag(it, 'pubDate'))).toISOString().slice(0, 10), url: text(tag(it, 'link')) };
  }).filter(x => x.title).sort((a, b) => b.date.localeCompare(a.date));
  if (!items.length) return { data: { query: q, items: [], note: 'no recent news found' } };
  return { data: { query: q, items }, ui: [{ c: 'sources', items: items.slice(0, 4).map(x => ({ title: `${x.source}: ${x.title}`, url: x.url })) }] };
}
