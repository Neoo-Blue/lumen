// Deterministic, zero-dependency email fact extractor for Lumen.
// Sources in trust order: JSON-LD > microdata > ICS > text/HTML patterns.

const CALENDAR_KINDS = new Set(['appointment', 'reservation', 'flight', 'hotel', 'event', 'bill_due']);
const MONTHS = { jan: 1, january: 1, feb: 2, february: 2, mar: 3, march: 3, apr: 4, april: 4, may: 5, jun: 6, june: 6, jul: 7, july: 7, aug: 8, august: 8, sep: 9, sept: 9, september: 9, oct: 10, october: 10, nov: 11, november: 11, dec: 12, december: 12 };
const MONTH_NAMES = ['', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const TZ_OFFSETS = { UTC: 'Z', GMT: 'Z', EST: '-05:00', EDT: '-04:00', CST: '-06:00', CDT: '-05:00', MST: '-07:00', MDT: '-06:00', PST: '-08:00', PDT: '-07:00', BST: '+01:00', CET: '+01:00', CEST: '+02:00', JST: '+09:00', SGT: '+08:00', HKT: '+08:00', AEST: '+10:00', AEDT: '+11:00' };
const WIN_TZ = { 'Pacific Standard Time': 'America/Los_Angeles', 'Mountain Standard Time': 'America/Denver', 'Central Standard Time': 'America/Chicago', 'Eastern Standard Time': 'America/New_York', 'GMT Standard Time': 'Europe/London', 'W. Europe Standard Time': 'Europe/Berlin', 'China Standard Time': 'Asia/Shanghai', 'Tokyo Standard Time': 'Asia/Tokyo' };
const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
const GENERIC_SENDERS = /^(no-?reply|donotreply|do-not-reply|notifications?|alerts?|info|support|billing|orders?|receipts?|mail|mailer|service|hello|team|calendar|reminders?)$/i;
const CUR_SYM = { $: 'USD', 'US$': 'USD', 'CA$': 'CAD', 'AU$': 'AUD', 'HK$': 'HKD', 'S$': 'SGD', '€': 'EUR', '£': 'GBP', '¥': 'CNY', '￥': 'CNY', '元': 'CNY', '人民币': 'CNY', RMB: 'CNY' };

const pad2 = n => String(n).padStart(2, '0');
const clean = s => String(s || '').replace(/\s+/g, ' ').trim();
const asArr = v => (v == null ? [] : Array.isArray(v) ? v : [v]);

function decodeEntities(s) {
  const map = { nbsp: ' ', amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", yen: '¥', euro: '€', pound: '£' };
  return String(s || '')
    .replace(/&(nbsp|amp|lt|gt|quot|apos|yen|euro|pound);/gi, (_, k) => map[k.toLowerCase()])
    .replace(/&#39;/g, "'")
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)));
}

function htmlToText(html) {
  if (!html) return '';
  return decodeEntities(String(html)
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(script|style|head)\b[^>]*>[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<(br|\/p|\/div|\/tr|\/li|\/h[1-6]|\/td|\/th)\b[^>]*>/gi, '\n')
    .replace(/<[^>]+>/g, ' '))
    .replace(/[ \t]+\n/g, '\n').replace(/\n[ \t]+/g, '\n')
    .replace(/[ \t]{2,}/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
}

function parseFrom(from) {
  const s = clean(from);
  if (!s) return '';
  const m = s.match(/^"?([^"<]+?)"?\s*<([^>]+)>$/);
  const name = m ? clean(m[1]) : '';
  const addr = m ? m[2] : (s.includes('@') ? s.replace(/[<>]/g, '') : '');
  if (name && !name.includes('@') && !GENERIC_SENDERS.test(name)) return name;
  if (addr.includes('@')) {
    const host = addr.split('@')[1].toLowerCase().replace(/^(mail|notify|email|alerts|orders|noreply|m|e)\./, '');
    const label = host.split('.')[0] || '';
    if (label && label !== 'example') return label[0].toUpperCase() + label.slice(1);
    return name || host;
  }
  return name || s;
}

function fmtAmount(amt) {
  if (!amt || typeof amt.value !== 'number') return '';
  const sym = { USD: '$', EUR: '€', GBP: '£', CNY: '¥', JPY: '¥' }[amt.currency];
  const num = Number.isInteger(amt.value) && amt.currency === 'JPY' ? String(amt.value) : amt.value.toFixed(2);
  return sym ? `${sym}${num}` : `${amt.currency} ${num}`;
}

function parseAmount(text) {
  if (!text) return null;
  const num = '(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)';
  const res = [
    new RegExp(`\\b(USD|EUR|GBP|CNY|RMB|JPY|CAD|AUD|HKD|SGD)\\s*[:$]?\\s*${num}`, 'i'),
    new RegExp(`(US\\$|CA\\$|AU\\$|HK\\$|S\\$|[$€£¥￥])\\s*${num}`),
    new RegExp(`${num}\\s*(USD|EUR|GBP|CNY|RMB|JPY|CAD|AUD|元|人民币)`, 'i'),
    new RegExp(`${num}\\s*([€£¥￥])`),
  ];
  for (const re of res) {
    const m = String(text).match(re);
    if (!m) continue;
    const codeFirst = !/^\d/.test(m[1]);
    const rawCur = (codeFirst ? m[1] : m[2]).trim();
    const rawNum = (codeFirst ? m[2] : m[1]).trim();
    const value = (/^\d{1,3}(\.\d{3})*,\d{2}$/.test(rawNum) || /^\d+,\d{2}$/.test(rawNum))
      ? parseFloat(rawNum.replace(/\./g, '').replace(',', '.'))
      : parseFloat(rawNum.replace(/,/g, ''));
    if (!Number.isFinite(value) || value <= 0 || value > 1e8) continue;
    return { value: Math.round(value * 100) / 100, currency: CUR_SYM[rawCur] || CUR_SYM[rawCur.toUpperCase()] || rawCur.toUpperCase() };
  }
  return null;
}

// --- Date & Time Parsing ---

function inferYear(month, day, refIso) {
  const ref = refIso ? new Date(refIso) : new Date();
  const y = Number.isNaN(ref.getTime()) ? new Date().getUTCFullYear() : ref.getUTCFullYear();
  if (Number.isNaN(ref.getTime())) return y;
  return (ref.getTime() - Date.UTC(y, month - 1, day)) > 30 * 86400000 ? y + 1 : y;
}

function buildIso(y, mo, d, h, mi, s, tz) {
  if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
  const chk = new Date(Date.UTC(y, mo - 1, d));
  if (chk.getUTCMonth() + 1 !== mo || chk.getUTCDate() !== d) return null;
  const dp = `${y}-${pad2(mo)}-${pad2(d)}`;
  if (h == null) return dp;
  if (h < 0 || h > 23 || mi < 0 || mi > 59) return null;
  return `${dp}T${pad2(h)}:${pad2(mi)}:${pad2(s || 0)}${tz || ''}`;
}

function parseTimePart(str) {
  if (!str) return null;
  const m = str.match(/(?:(上午|下午|晚上|中午)\s*)?(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?(?:\s*(UTC|GMT|EST|EDT|CST|CDT|MST|MDT|PST|PDT|BST|CET|CEST|JST|SGT|HKT|AEST|AEDT|Z|[+-]\d{2}:?\d{2}))?/i);
  if (!m) return null;
  let h = +m[2];
  const cn = m[1], en = (m[5] || '').replace(/\./g, '').toLowerCase();
  if (en === 'pm' || cn === '下午' || cn === '晚上') { if (h < 12) h += 12; }
  else if ((en === 'am' || cn === '上午') && h === 12) h = 0;
  let tz = '';
  if (m[6]) {
    const r = m[6].toUpperCase();
    tz = r === 'Z' ? 'Z' : (TZ_OFFSETS[r] || (/^[+-]\d{4}$/.test(r) ? `${r.slice(0, 3)}:${r.slice(3)}` : (/^[+-]\d{2}:\d{2}$/.test(r) ? r : '')));
  }
  return { h, mi: +m[3], s: m[4] ? +m[4] : 0, tz };
}

function parseDateTime(text, refIso) {
  if (!text) return null;
  const s = String(text);
  const finish = (y, mo, d, tRaw) => { const tp = parseTimePart(tRaw); return buildIso(y, mo, d, tp?.h ?? null, tp?.mi ?? 0, tp?.s ?? 0, tp?.tz ?? ''); };
  // ISO: 2026-12-02T15:30:00Z or 2026-12-02 15:30
  let m = s.match(/\b(\d{4})-(\d{2})-(\d{2})(?:[T\s]+(\d{2}):(\d{2})(?::(\d{2}))?(Z|[+-]\d{2}:?\d{2})?)?\b/);
  if (m) return buildIso(+m[1], +m[2], +m[3], m[4] != null ? +m[4] : null, +(m[5] || 0), +(m[6] || 0), /^[+-]\d{4}$/.test(m[7] || '') ? `${m[7].slice(0, 3)}:${m[7].slice(3)}` : (m[7] || ''));
  // Chinese: 2026年12月2日 15:30 / 12月2日 下午3:30
  m = s.match(/(?:(\d{4})年)?(\d{1,2})月(\d{1,2})[日号](?:[\s,，]*(?:星期[一二三四五六日天]|周[一二三四五六日天])?[\s,，]*((?:上午|下午|晚上|中午)?\s*\d{1,2}:\d{2}(?::\d{2})?(?:\s*(?:AM|PM|UTC|GMT|CST|PST|EST|[+-]\d{2}:?\d{2}))?))?/i);
  if (m) return finish(m[1] ? +m[1] : inferYear(+m[2], +m[3], refIso), +m[2], +m[3], m[4]);
  const monPat = '(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t|tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)';
  const tTail = '(?:[,\\s]+(?:at\\s+)?(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?(?:\\s*(?:UTC|GMT|EST|EDT|CST|CDT|MST|MDT|PST|PDT|BST|CET|CEST|JST|SGT|HKT|[+-]\\d{2}:?\\d{2}))?))?';
  // Month Day[, Year] [Time]
  m = s.match(new RegExp(`\\b${monPat}\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?${tTail}`, 'i'));
  if (m) { const mo = MONTHS[m[1].toLowerCase()]; return finish(m[3] ? +m[3] : inferYear(mo, +m[2], refIso), mo, +m[2], m[4]); }
  // Day Month Year [Time]
  m = s.match(new RegExp(`\\b(\\d{1,2})\\s+${monPat}\\.?\\s+(\\d{4})${tTail}`, 'i'));
  if (m) return finish(+m[3], MONTHS[m[2].toLowerCase()], +m[1], m[4]);
  // YYYY/MM/DD or MM/DD/YYYY [Time]
  m = s.match(new RegExp(`\\b(\\d{4})/(\\d{1,2})/(\\d{1,2})${tTail}`, 'i'));
  if (m) return finish(+m[1], +m[2], +m[3], m[4]);
  m = s.match(new RegExp(`\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})${tTail}`, 'i'));
  if (m) { let a = +m[1], b = +m[2]; if (a > 12 && b <= 12) [a, b] = [b, a]; return finish(+m[3], a, b, m[4]); }
  return null;
}

function icsTimeToIso(val, tzid) {
  const v = clean(val);
  let m = v.match(/^(\d{4})(\d{2})(\d{2})$/);
  if (m) return `${m[1]}-${m[2]}-${m[3]}`;
  m = v.match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(Z?)$/);
  if (!m) return null;
  const [, y, mo, d, h, mi, s, z] = m;
  let tz = z ? 'Z' : '';
  if (!z && tzid) {
    try {
      const zone = WIN_TZ[tzid] || tzid;
      const probe = utc => {
        const ps = new Intl.DateTimeFormat('en-US', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(utc));
        const g = t => +ps.find(p => p.type === t).value;
        return Date.UTC(g('year'), g('month') - 1, g('day'), g('hour'), g('minute'), g('second'));
      };
      const guess = Date.UTC(+y, +mo - 1, +d, +h, +mi, +s);
      let diff = Math.round((probe(guess) - guess) / 60000);
      diff = Math.round((probe(guess - diff * 60000) - (guess - diff * 60000)) / 60000);
      tz = `${diff >= 0 ? '+' : '-'}${pad2(Math.floor(Math.abs(diff) / 60))}:${pad2(Math.abs(diff) % 60)}`;
    } catch { tz = ''; }
  }
  return `${y}-${mo}-${d}T${h}:${mi}:${s}${tz}`;
}

function formatShortWhen(iso) {
  const m = String(iso || '').match(/^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/);
  if (!m) return '';
  return m[4] ? `${MONTH_NAMES[+m[2]]} ${+m[3]} ${m[4]}:${m[5]}` : `${MONTH_NAMES[+m[2]]} ${+m[3]}`;
}

// --- Shared Schema.org Mapper (JSON-LD & Microdata) ---

const schemaTail = v => clean(v).replace(/^https?:\/\/schema\.org\//i, '');
const schemaName = v => (!v ? '' : typeof v === 'string' ? clean(v) : Array.isArray(v) ? schemaName(v[0]) : clean(v.name || v.legalName || v.iataCode || ''));
const isCancelled = st => /cancell?ed/i.test(schemaTail(st));

function schemaAddr(addr) {
  if (!addr) return null;
  if (typeof addr === 'string') return clean(addr) || null;
  const parts = [addr.name, addr.streetAddress, addr.addressLocality, addr.addressRegion].map(clean).filter(Boolean);
  return parts.length ? parts.join(', ') : null;
}

function schemaAmount(obj) {
  if (!obj) return null;
  const p = obj.price ?? obj.totalPrice, c = obj.priceCurrency || 'USD';
  if (p == null) return null;
  if (typeof p === 'object') return schemaAmount(p);
  if (typeof p === 'number' && p > 0) return { value: Math.round(p * 100) / 100, currency: clean(c).toUpperCase() };
  return parseAmount(`${c} ${p}`) || parseAmount(String(p));
}

function makeItem(f) {
  const when = f.when || null;
  return {
    kind: f.kind, title: clean(f.title).slice(0, 140), who: clean(f.who) || 'Unknown',
    amount: f.amount || null, when, end: f.end || null,
    where: f.where ? clean(f.where) : null, ref: f.ref ? clean(f.ref) : null,
    calendar: CALENDAR_KINDS.has(f.kind) && Boolean(when),
    confidence: f.confidence ?? 0.9, source: f.source,
  };
}

function mapSchemaEntity(e, mail, source) {
  if (!e || typeof e !== 'object') return null;
  const types = asArr(e['@type']).map(t => schemaTail(t).toLowerCase());
  const has = t => types.includes(t.toLowerCase());
  const fromName = parseFrom(mail.from);
  const conf = source === 'jsonld' ? 0.95 : 0.9;
  const norm = v => (v ? parseDateTime(String(v), mail.date) || clean(v) : null);
  const rf = e.reservationFor || {};

  const out = (kind, title, extra) => makeItem({ kind, title, confidence: conf, source, ...extra });

  if (has('Order')) {
    const st = schemaTail(e.orderStatus);
    const kind = isCancelled(st) ? 'cancelled' : /delivered/i.test(st) ? 'delivered' : /intransit|shipped/i.test(st) ? 'shipped' : /returned/i.test(st) ? 'return' : /paymentdue/i.test(st) ? 'bill_due' : 'order';
    const who = schemaName(e.merchant) || schemaName(e.seller) || schemaName(e.broker) || fromName;
    const offers = asArr(e.acceptedOffer), item = schemaName(offers[0]?.itemOffered) || schemaName(e.orderedItem);
    const lbl = { cancelled: 'Order cancelled', delivered: 'Order delivered', shipped: 'Order shipped', return: 'Order returned', bill_due: 'Payment due', order: 'Order' }[kind];
    const when = kind === 'bill_due' ? norm(e.paymentDueDate || e.paymentDue)?.slice(0, 10) : norm(e.orderDate);
    return out(kind, item ? `${lbl}: ${item}` : `${lbl} from ${who}`, { who, amount: schemaAmount(e) || schemaAmount(offers[0]), when, ref: e.orderNumber || e.confirmationNumber });
  }
  if (has('ParcelDelivery')) {
    const st = schemaTail(e.deliveryStatus || e.partOfOrder?.orderStatus);
    const kind = isCancelled(st) ? 'cancelled' : /delivered/i.test(st) ? 'delivered' : /outfordelivery/i.test(st) ? 'out_for_delivery' : 'shipped';
    const who = schemaName(e.carrier) || schemaName(e.provider) || schemaName(e.partOfOrder?.merchant) || fromName;
    const item = schemaName(e.itemShipped) || schemaName(e.partOfOrder?.acceptedOffer?.itemOffered);
    const lbl = { delivered: 'Package delivered', out_for_delivery: 'Out for delivery', cancelled: 'Delivery cancelled', shipped: 'Package shipped' }[kind];
    return out(kind, item ? `${lbl}: ${item}` : `${lbl} via ${who}`, { who, when: norm(e.expectedArrivalFrom || e.expectedArrivalUntil), where: schemaAddr(e.deliveryAddress), ref: e.trackingNumber || e.partOfOrder?.orderNumber });
  }
  if (has('Invoice')) {
    const st = schemaTail(e.paymentStatus);
    const kind = /complete|paid/i.test(st) ? 'receipt' : /declined|failed/i.test(st) ? 'payment_failed' : 'bill_due';
    const who = schemaName(e.provider) || schemaName(e.broker) || fromName;
    const amount = schemaAmount(e.totalPaymentDue) || schemaAmount(e.minimumPaymentDue) || schemaAmount(e);
    const rawDue = norm(e.paymentDueDate || e.paymentDue), amt = amount ? ` ${fmtAmount(amount)}` : '';
    const title = kind === 'bill_due' ? `Bill due${amt} to ${who}` : kind === 'payment_failed' ? `Payment failed${amt} to ${who}` : `Receipt${amt} from ${who}`;
    return out(kind, title, { who, amount, when: kind === 'bill_due' && rawDue ? rawDue.slice(0, 10) : rawDue, ref: e.confirmationNumber || e.accountId || e.identifier });
  }
  if (has('FlightReservation')) {
    const canc = isCancelled(e.reservationStatus);
    const who = schemaName(rf.airline) || schemaName(rf.provider) || schemaName(e.provider) || fromName;
    const dep = clean(rf.departureAirport?.iataCode || schemaName(rf.departureAirport)), arr = clean(rf.arrivalAirport?.iataCode || schemaName(rf.arrivalAirport));
    const route = dep && arr ? `${dep} to ${arr}` : (dep || arr || '');
    const code = clean(`${rf.airline?.iataCode || ''} ${rf.flightNumber || ''}`);
    return out(canc ? 'cancelled' : 'flight', `${canc ? 'Flight cancelled: ' : 'Flight '}${[code, route].filter(Boolean).join(' ') || who}`, { who, amount: schemaAmount(e), when: norm(rf.departureTime), end: norm(rf.arrivalTime), where: route || null, ref: e.reservationNumber });
  }
  if (has('LodgingReservation')) {
    const canc = isCancelled(e.reservationStatus), who = schemaName(rf) || schemaName(e.provider) || fromName;
    return out(canc ? 'cancelled' : 'hotel', canc ? `Hotel cancelled: ${who}` : `Hotel check-in: ${who}`, { who, amount: schemaAmount(e), when: norm(e.checkinTime || e.checkinDate), end: norm(e.checkoutTime || e.checkoutDate), where: schemaAddr(rf.address) || schemaName(rf) || null, ref: e.reservationNumber });
  }
  if (has('FoodEstablishmentReservation')) {
    const canc = isCancelled(e.reservationStatus), who = schemaName(rf) || schemaName(e.provider) || fromName;
    return out(canc ? 'cancelled' : 'reservation', canc ? `Reservation cancelled: ${who}` : `Table${e.partySize ? ` for ${e.partySize}` : ''} at ${who}`, { who, when: norm(e.startTime), end: norm(e.endTime), where: schemaAddr(rf.address) || schemaName(rf) || null, ref: e.reservationNumber });
  }
  if (has('EventReservation')) {
    const canc = isCancelled(e.reservationStatus), name = schemaName(rf) || 'Event';
    const who = schemaName(rf.organizer) || schemaName(e.provider) || schemaName(rf.location) || fromName;
    const addr = schemaAddr(rf.location?.address), where = addr ? `${schemaName(rf.location)}, ${addr}`.replace(/^,\s*/, '') : (schemaName(rf.location) || null);
    return out(canc ? 'cancelled' : 'event', canc ? `Event cancelled: ${name}` : name, { who, amount: schemaAmount(e), when: norm(rf.startDate || e.startTime), end: norm(rf.endDate || e.endTime), where, ref: e.reservationNumber });
  }
  if (has('RentalCarReservation') || has('TrainReservation') || has('BusReservation')) {
    const canc = isCancelled(e.reservationStatus), isCar = has('RentalCarReservation'), lbl = isCar ? 'Car rental' : has('TrainReservation') ? 'Train' : 'Bus';
    const who = schemaName(e.provider) || schemaName(rf.provider) || schemaName(rf.rentalCompany) || fromName;
    const dep = schemaName(rf.departureStation || rf.departureBusStop), arr = schemaName(rf.arrivalStation || rf.arrivalBusStop);
    const where = isCar ? (schemaName(e.pickupLocation) || schemaAddr(e.pickupLocation?.address)) : (dep && arr ? `${dep} to ${arr}` : (dep || arr || null));
    return out(canc ? 'cancelled' : 'reservation', canc ? `${lbl} cancelled: ${who}` : `${lbl}${where ? `: ${where}` : ` with ${who}`}`, { who, amount: schemaAmount(e), when: norm(isCar ? e.pickupTime : rf.departureTime), end: norm(isCar ? e.dropoffTime : rf.arrivalTime), where, ref: e.reservationNumber });
  }
  return null;
}

// --- Source 1: JSON-LD ---

function extractJsonLd(html, mail) {
  if (!html) return [];
  const items = [], re = /<script\b[^>]*type\s*=\s*["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    let data;
    try { data = JSON.parse(m[1].trim()); } catch { continue; }
    const q = asArr(data);
    while (q.length) {
      const n = q.shift();
      if (!n || typeof n !== 'object') continue;
      if (Array.isArray(n)) { q.push(...n); continue; }
      if (Array.isArray(n['@graph'])) q.push(...n['@graph']);
      const it = mapSchemaEntity(n, mail, 'jsonld');
      if (it) items.push(it);
    }
  }
  return items;
}

// --- Source 2: Microdata ---

function addProp(obj, prop, val) {
  if (!obj || !prop || val === '' || val == null) return;
  if (obj[prop] === undefined) obj[prop] = val;
  else if (Array.isArray(obj[prop])) obj[prop].push(val);
  else obj[prop] = [obj[prop], val];
}

function extractMicrodata(html, mail) {
  if (!html || !/itemscope/i.test(html)) return [];
  const top = [], stack = [], tagRe = /<(\/?)([a-zA-Z][a-zA-Z0-9:-]*)([^>]*?)(\/?)>/g;
  const attrRe = /([a-zA-Z_:][\w:.-]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
  let lastIdx = 0, m;
  const nearestScope = () => { for (let i = stack.length - 1; i >= 0; i--) if (stack[i].scope) return stack[i].scope; return null; };

  while ((m = tagRe.exec(html)) !== null) {
    if (m.index > lastIdx && stack.length) {
      const txt = decodeEntities(html.slice(lastIdx, m.index));
      for (const f of stack) if (f.collectText) f.text += txt;
    }
    lastIdx = tagRe.lastIndex;
    const isClose = m[1] === '/', tag = m[2].toLowerCase(), selfClose = m[4] === '/' || VOID_TAGS.has(tag);
    if (isClose) {
      for (let i = stack.length - 1; i >= 0; i--) {
        if (stack[i].tag !== tag) continue;
        for (const f of stack.splice(i)) if (f.collectText && f.parent && f.prop) addProp(f.parent, f.prop, clean(f.text));
        break;
      }
      continue;
    }
    const attrs = {};
    for (const am of m[3].matchAll(attrRe)) attrs[am[1].toLowerCase()] = am[2] ?? am[3] ?? am[4] ?? '';
    const parent = nearestScope(), prop = attrs.itemprop || '';
    let scope = null, collectText = false;
    if ('itemscope' in attrs) {
      scope = attrs.itemtype ? { '@type': schemaTail(attrs.itemtype) } : {};
      if (prop && parent) addProp(parent, prop, scope); else top.push(scope);
    } else if (prop && parent) {
      const v = attrs.content ?? attrs.href ?? attrs.datetime ?? attrs.value ?? attrs.src;
      if (v !== undefined) addProp(parent, prop, clean(decodeEntities(v)));
      else if (!selfClose) collectText = true;
    }
    if (!selfClose) stack.push({ tag, scope, parent, prop, collectText, text: '' });
  }
  return top.map(e => mapSchemaEntity(e, mail, 'microdata')).filter(Boolean);
}

// --- Source 3: iCalendar (ICS) ---

const unescapeIcs = s => String(s || '').replace(/\\[nN]/g, ' ').replace(/\\,/g, ',').replace(/\\;/g, ';').replace(/\\\\/g, '\\').trim();

function parseIcsBlock(raw, mail) {
  const lines = String(raw).replace(/\r?\n[ \t]/g, '').split(/\r?\n/);
  const items = [], fromName = parseFrom(mail.from);
  let method = '', ev = null;

  for (const line of lines) {
    if (/^BEGIN:VEVENT$/i.test(line)) { ev = {}; continue; }
    if (/^END:VEVENT$/i.test(line)) {
      if (ev) {
        const canc = /^CANCEL$/i.test(method) || /^CANCELLED$/i.test(ev.status || '');
        const sum = ev.summary || 'Calendar event';
        const kind = canc ? 'cancelled' : /\bflight\b/i.test(sum) ? 'flight' : /\bhotel|check[- ]?in\b/i.test(sum) ? 'hotel'
          : /\b(table|dinner|lunch|reservation)\b/i.test(sum) ? 'reservation'
          : /\b(concert|conference|summit|webinar|festival|game|match|show|party)\b/i.test(sum) ? 'event' : 'appointment';
        const who = (ev.organizer && !ev.organizer.includes('@') ? ev.organizer : '') || fromName;
        items.push(makeItem({ kind, title: canc ? `Cancelled: ${sum}` : sum, who, when: ev.start, end: ev.end, where: ev.location, ref: ev.uid, confidence: 0.95, source: 'ics' }));
      }
      ev = null; continue;
    }
    const idx = line.indexOf(':');
    if (idx === -1) continue;
    const [propRaw, ...pParts] = line.slice(0, idx).split(';');
    const prop = propRaw.toUpperCase(), val = line.slice(idx + 1), params = {};
    for (const p of pParts) { const eq = p.indexOf('='); if (eq !== -1) params[p.slice(0, eq).toUpperCase()] = p.slice(eq + 1).replace(/^"|"$/g, ''); }
    if (!ev) { if (prop === 'METHOD') method = clean(val); continue; }
    if (prop === 'SUMMARY') ev.summary = unescapeIcs(val);
    else if (prop === 'LOCATION') ev.location = unescapeIcs(val);
    else if (prop === 'STATUS') ev.status = clean(val);
    else if (prop === 'UID') ev.uid = clean(val);
    else if (prop === 'ORGANIZER') ev.organizer = params.CN ? unescapeIcs(params.CN) : clean(val.replace(/^mailto:/i, ''));
    else if (prop === 'DTSTART') ev.start = icsTimeToIso(val, params.TZID);
    else if (prop === 'DTEND') ev.end = icsTimeToIso(val, params.TZID);
  }
  return items;
}

function extractIcs(mail) {
  const items = [];
  for (const att of asArr(mail.attachments)) {
    if (att && typeof att.content === 'string' && (/text\/calendar|application\/ics/i.test(att.contentType || '') || /\.ics$/i.test(att.filename || '')) && /BEGIN:VCALENDAR/i.test(att.content)) {
      items.push(...parseIcsBlock(att.content, mail));
    }
  }
  if (mail.text && /BEGIN:VCALENDAR/i.test(mail.text)) items.push(...parseIcsBlock(mail.text, mail));
  return items;
}

// --- Source 4: Pattern Extraction (EN + Simplified Chinese) ---

const NOISE_SUBJ = /\b(newsletter|weekly digest|daily digest|roundup|top stories|sale ends|flash sale|black friday|cyber monday|% off|order now|shop now|buy now|limited[- ]time offer|promo code|use code|policy update|privacy policy|terms of service|refund policy|return policy|cancellation policy|how refunds work|tips for|guide to|liked your|commented on|replied to your|started following|new follower|tagged you|friend request|accepted your invitation to connect)\b/i;
const NOISE_BODY = /\b(weekly digest|top stories this week|shop the sale|use promo code|unsubscribe from this newsletter|liked your post|commented on your|started following you|view their profile|our updated refund policy|our return policy explains|frequently asked questions about refunds)\b/i;

function extractRef(text) {
  const res = [
    /(?:tracking|shipment)\s*(?:number|no\.?|id|#)?\s*[:#]\s*([A-Z0-9][A-Z0-9-]{5,28})/i,
    /\b(1Z[A-Z0-9]{16})\b/,
    /(?:order|confirmation|booking|reservation|invoice|reference|receipt|record locator|pnr|ticket)\s*(?:number|no\.?|code|id|#)?\s*[:#]\s*([A-Z0-9][A-Z0-9-]{3,28})/i,
    /(?:order|invoice|booking|reservation|confirmation|ref)\s+#([A-Z0-9][A-Z0-9-]{3,24})/i,
    /(?:订单号|运单号|快递单号|确认码|预订号|交易单号|账单号|参考号)[：:\s#]*([A-Z0-9][A-Z0-9-]{3,28})/i,
  ];
  const bad = /^(number|code|confirmed|confirmation|details|status|summary|received|cancelled|canceled|date|total|today|online|here|your|this|that|from|with)$/i;
  for (const re of res) {
    const m = text.match(re);
    if (!m) continue;
    const c = m[1].replace(/[.,;)]+$/, '');
    if (!bad.test(c) && (/\d/.test(c) || /^[A-Z]{5,6}$/.test(c))) return c;
  }
  return null;
}

function extractPattern(mail) {
  const subject = clean(mail.subject);
  const rawBody = mail.text ? String(mail.text) : htmlToText(mail.html);
  const body = rawBody.split(/\n(?:[-_]{2,}\s*(?:Original Message|Forwarded message)\s*[-_]{2,}|On .{10,100} wrote:)/i)[0]
    .split('\n').filter(l => !/^\s*>/.test(l)).join('\n').trim();
  const full = `${subject}\n${body}`;
  if (!full.trim() || NOISE_SUBJ.test(subject) || NOISE_BODY.test(body) || /^(re|fwd|fw)\s*:/i.test(subject)) return [];

  const fromName = parseFrom(mail.from);
  const ref = extractRef(full);
  const amount = parseAmount(full);
  const whereM = body.match(/(?:location|address|venue|where|place|地点|地址|门店)[：:\s]+([^\n]{3,90})/i);
  const where = whereM ? clean(whereM[1]).replace(/[.;]+$/, '') : null;
  const labeledDate = re => { const m = body.match(re); return m ? parseDateTime(m[1], mail.date) : null; };
  const hit = (kind, title, extra = {}) => [makeItem({ kind, title, who: fromName, confidence: 0.82, source: 'pattern', ...extra })];
  const amtStr = a => (a ? ` ${fmtAmount(a)}` : '');

  // 1. Security code (never include the code itself in any field)
  if ((/\b(verification code|sign[- ]?in code|login code|one[- ]?time (?:passcode|password|code)|security code|auth(?:entication)? code|2fa code|otp)\b/i.test(full) && /(\b\d{4,8}\b|enter (?:this |the )?code|use (?:this |the )?code|expires in)/i.test(full))
    || (/(验证码|动态码|校验码|登录码|安全码)/.test(full) && /(\d{4,8}|有效|切勿|请输入)/.test(full))) {
    const safeWho = fromName.replace(/\b\d{4,8}\b/g, '').trim() || 'Unknown';
    return hit('security_code', `Sign-in code from ${safeWho}`, { who: safeWho, confidence: 0.9 });
  }
  // 2. Security alert
  const alertM = full.match(/\b(new sign[- ]?in|new login|unusual sign[- ]?in|suspicious (?:sign[- ]?in|login|activity)|password (?:was |has been )?(?:changed|reset|updated)|security alert|new device (?:signed in|logged in|added)|two[- ]factor (?:disabled|removed|turned off))\b/i)
    || full.match(/(新设备登录|异常登录|密码已修改|密码已重置|安全提醒|安全警报|异地登录)/);
  if (alertM) {
    const locM = body.match(/(?:location|near|from)\s*[:：]?\s*([A-Z][a-zA-Z .'-]+,\s*[A-Z]{2,}(?:,\s*[A-Z][a-zA-Z ]+)?)/);
    return hit('security_alert', `Security alert: ${/password|密码/i.test(alertM[0]) ? 'Password changed' : 'New sign-in'} (${fromName})`, { when: labeledDate(/(?:time|when|date|时间)[：:\s]+([^\n]{6,60})/i), where: where || (locM ? clean(locM[1]) : null), confidence: 0.85 });
  }
  // 3. Cancelled
  if (!/\b(cancellation policy|free cancellation|cancel anytime|how to cancel|to cancel your)\b/i.test(full) && (
    /\b(?:your\s+)?(order|booking|reservation|appointment|flight|hotel|trip|delivery|subscription|ticket|shipment)\b[^.\n]{0,60}\b(?:has been|was|is)\s+cancell?ed\b/i.test(full)
    || /\bcancell?ation confirm(?:ed|ation)\b/i.test(full) || /\b(order|booking|reservation|appointment|flight|subscription)\b.*\bcancell?ed\b/i.test(subject)
    || /^cancell?ed:\s+/i.test(subject) || /(订单|预约|预订|航班|酒店|行程|订阅).{0,12}(已取消|取消成功)|取消确认/.test(full)))
    return hit('cancelled', `Cancelled: ${subject || fromName}`, { amount, ref });
  // 4. Refund
  if (!/\b(refund policy|eligible for a refund|how to request a refund)\b/i.test(full) && (
    /\b(refund (?:has been )?(?:issued|processed|sent|approved|completed)|we(?:'ve| have)? (?:issued|processed|sent) (?:a |your )?refund|your refund (?:of|for|has)|you received a refund)\b/i.test(full)
    || /\b(refund (?:issued|processed|confirmation|notification)|your refund)\b/i.test(subject) || /(退款成功|已退款|退款已到账|退款通知|退款确认)/.test(full)))
    return hit('refund', `Refund${amtStr(amount)} from ${fromName}`, { amount, ref, confidence: 0.85 });
  // 5. Return
  if (!/\b(return policy|free returns)\b/i.test(full) && (/\b(return (?:has been )?(?:received|approved|processed|confirmed)|we(?:'ve| have)? received your return|return confirmation)\b/i.test(full) || /(退货已签收|退货申请已通过|退货确认|已收到您的退货)/.test(full)))
    return hit('return', `Return confirmed by ${fromName}`, { amount, ref });
  // 6. Payment failed
  if (/\b(payment (?:was )?(?:failed|declined|unsuccessful|rejected|could not be processed)|card (?:was )?(?:declined|failed)|charge (?:failed|declined)|unable to process your payment)\b/i.test(full) || /(支付失败|扣款失败|付款失败|交易失败)/.test(full))
    return hit('payment_failed', `Payment failed${amtStr(amount)} to ${fromName}`, { amount, ref, confidence: 0.86 });
  // 7. Delivered
  if (!/\b(delivered to your inbox|free delivery|get it delivered)\b/i.test(full) && (
    /\b(?:your\s+)?(?:package|order|parcel|shipment|delivery|item)\s+(?:was|has been)\s+delivered\b/i.test(full)
    || /\bdelivered:\s/i.test(subject) || /\bwas delivered (?:to|at|on)\b/i.test(body) || /(已签收|已送达|快件已签收|包裹已送达)/.test(full))) {
    const im = full.match(/(?:delivered:\s*|item:\s*|package containing\s+)([^\n.]{3,50})/i);
    return hit('delivered', im ? `Package delivered: ${clean(im[1])}` : `Package delivered from ${fromName}`, { ref, where, when: labeledDate(/(?:delivered (?:on|at)|签收时间)[：:\s]+([^\n]{6,60})/i), confidence: 0.84 });
  }
  // 8. Out for delivery
  if (/\b(out for delivery|on (?:the )?vehicle for delivery|will be delivered today)\b/i.test(full) || /(派送中|正在派送|正在为您派件)/.test(full))
    return hit('out_for_delivery', `Out for delivery from ${fromName}`, { ref, where, when: mail.date ? mail.date.slice(0, 10) : null, confidence: 0.84 });
  // 9. Shipped
  if (!/\b(free shipping on|ships in \d+ days)\b/i.test(full) && (
    /\b(?:your\s+)?(?:order|package|item|parcel|shipment)\s+(?:has|was)\s+shipped\b/i.test(full)
    || /\b(shipment confirmation|shipping confirmation|has been dispatched|is on its way)\b/i.test(full) || /(已发货|发货通知|包裹已发出)/.test(full)))
    return hit('shipped', `Package shipped from ${fromName}`, { ref, where, when: labeledDate(/(?:estimated delivery|arriving|expected|预计送达)[：:\s]+([^\n]{5,60})/i) });
  // 10. Flight
  if (/\b(flight (?:confirmation|itinerary|booking|booked|receipt)|boarding pass|e-?ticket|your (?:upcoming )?flight)\b/i.test(full) || /(航班确认|机票确认|行程单|出票成功)/.test(full)) {
    const flt = full.match(/\b([A-Z]{2})\s?(\d{2,4})\b/), rt = full.match(/\b([A-Z]{3})\s*(?:->|→|to|-)\s*([A-Z]{3})\b/);
    const route = rt ? `${rt[1]} to ${rt[2]}` : null;
    const when = labeledDate(/(?:depart(?:s|ure|ing)?|起飞)[：:\s]+([^\n]{6,60})/i) || parseDateTime(body, mail.date);
    return hit('flight', `Flight ${[flt ? `${flt[1]} ${flt[2]}` : '', route].filter(Boolean).join(' ') || fromName}`, { amount, when, end: labeledDate(/(?:arriv(?:es|al|ing)?|到达)[：:\s]+([^\n]{6,60})/i), where: route || where, ref });
  }
  // 11. Hotel
  if (/\b(hotel (?:reservation|booking|confirmation)|your stay at|lodging confirmation|room reservation)\b/i.test(full) || /(酒店预订|入住确认|预订成功.*入住)/.test(full)) {
    const stayM = full.match(/your stay at\s+([A-Z][a-zA-Z0-9 &'.,-]{1,45}?)(?:\s+(?:is|was|has|from|for|on)\b|[.,!\n]|$)/i);
    const hotel = stayM ? clean(stayM[1]) : fromName;
    const when = labeledDate(/(?:check[- ]?in|入住)[：:\s]+([^\n]{5,60})/i) || parseDateTime(body, mail.date);
    return hit('hotel', `Hotel check-in: ${hotel}`, { who: hotel, amount, when, end: labeledDate(/(?:check[- ]?out|离店|退房)[：:\s]+([^\n]{5,60})/i), where: where || hotel, ref });
  }
  // 12. Appointment
  if (/\b(appointment (?:is )?(?:confirmed|scheduled|booked|reminder)|your (?:upcoming )?appointment|consultation (?:is )?(?:confirmed|scheduled))\b/i.test(full) || /(预约成功|预约确认|预约提醒|就诊提醒)/.test(full)) {
    const when = labeledDate(/(?:when|date(?: & time)?|time|scheduled for|时间|预约时间)[：:\s]+([^\n]{5,70})/i) || parseDateTime(full, mail.date);
    const lm = subject.match(/^(.{3,45}?)\s+appointment/i) || body.match(/(?:service|appointment|预约项目)[：:\s]+([^\n]{2,50})/i);
    const lbl = lm ? clean(lm[1]).replace(/^your\s+/i, '') : 'Appointment';
    return hit('appointment', when ? `${lbl}, ${formatShortWhen(when)}` : `${lbl} with ${fromName}`, { when, where, ref });
  }
  // 13. Reservation
  if (/\b((?:table|dinner|lunch|restaurant|car|train|bus)\s+reservation|your reservation (?:is )?(?:confirmed|at|for)|booking confirmed|confirmed reservation)\b/i.test(full) || /(预订确认|预订成功|订座成功|车票预订)/.test(full)) {
    const when = labeledDate(/(?:when|date(?: & time)?|time|for|时间)[：:\s]+([^\n]{5,70})/i) || parseDateTime(full, mail.date);
    const am = full.match(/reservation (?:at|with)\s+([A-Z][a-zA-Z0-9 &'.,-]{1,45}?)(?:\s+(?:is|was|has|for|on|at)\b|[.,!\n]|$)/i);
    const who = am ? clean(am[1]) : fromName;
    return hit('reservation', when ? `Reservation at ${who}, ${formatShortWhen(when)}` : `Reservation at ${who}`, { who, amount, when, where: where || who, ref, confidence: 0.8 });
  }
  // 14. Event / Tickets
  if (/\b(your tickets? (?:for|to|are)|event (?:confirmation|registration|ticket)|you(?:'re| are) registered for|rsvp confirmed)\b/i.test(full) || /(购票成功|活动报名成功|演出票确认)/.test(full)) {
    const when = labeledDate(/(?:when|date(?: & time)?|starts?|时间)[：:\s]+([^\n]{5,70})/i) || parseDateTime(full, mail.date);
    const em = full.match(/(?:tickets? (?:for|to)|registered for)\s+([^\n.!]{3,60})/i);
    return hit('event', em ? clean(em[1]) : (subject || `Event from ${fromName}`), { amount, when, where, ref, confidence: 0.8 });
  }
  // 15. Bill due (all-day calendar)
  if (/\b(payment (?:is )?due|bill (?:is )?(?:ready|due)|statement (?:is )?(?:ready|available)|amount due|minimum payment due|due (?:on|by)\s+[A-Z0-9])/i.test(full) || /(账单.*到期|还款日|应还金额|缴费通知|账单已出)/.test(full)) {
    const rawDue = labeledDate(/(?:due(?:\s+date)?|due\s+(?:on|by)|pay\s+by|还款日|到期日)[：:\s]+([^\n]{5,60})/i) || parseDateTime(body, mail.date);
    const dueAmt = parseAmount((body.match(/(?:amount due|total due|balance due|应还(?:金额)?)[：:\s]+([^\n]{2,30})/i) || [])[1]) || amount;
    return hit('bill_due', `Bill due${amtStr(dueAmt)} to ${fromName}`, { amount: dueAmt, when: rawDue ? rawDue.slice(0, 10) : null, ref });
  }
  // 15b. Free trial ending (a charge follows unless cancelled)
  if (/\b((?:free )?trial (?:ends|will end|expires|is ending)|your trial (?:period )?(?:ends|expires)|before your trial ends)\b/i.test(full) || /(试用期?(?:即将)?(?:结束|到期))/.test(full)) {
    const when = labeledDate(/(?:trial (?:ends|expires)(?: on)?|ends on|试用(?:期)?(?:结束|到期)(?:日期)?)[：:\s]+([^\n]{5,60})/i) || parseDateTime(body, mail.date);
    return hit('trial_ending', `${fromName} trial ends${amount ? `, then ${fmtAmount(amount)}` : ''}`, { amount, when: when ? when.slice(0, 10) : null, ref, confidence: 0.8 });
  }
  // 15c. Return window closing
  if (/\b(return (?:window|period) (?:closes|ends)|return (?:it )?by|eligible for return (?:until|through)|last day to return)\b/i.test(full) || /(退货(?:截止|期限)|最后退货日)/.test(full)) {
    const when = labeledDate(/(?:return (?:by|until|through)|closes(?: on)?|ends(?: on)?|last day to return(?: is)?|退货截止(?:日期)?)[：:\s]+([^\n]{5,60})/i) || parseDateTime(body, mail.date);
    return hit('return_deadline', `Return window closes at ${fromName}`, { amount, when: when ? when.slice(0, 10) : null, ref, confidence: 0.75 });
  }
  // 16. Subscription renewal
  if (/\b((?:subscription|membership|plan)\s+(?:will|is set to)\s+(?:auto-?)?renew|upcoming renewal|renewal (?:reminder|notice)|will (?:automatically )?renew on)\b/i.test(full) || /(自动续费|即将续费|会员续费提醒|订阅续费)/.test(full)) {
    const when = labeledDate(/(?:renew(?:s|al|ing)?\s+(?:on|date)|续费日期|扣款日期)[：:\s]+([^\n]{5,60})/i) || parseDateTime(body, mail.date);
    return hit('subscription_renewal', `${fromName} renewal${amount ? ` (${fmtAmount(amount)})` : ''}`, { amount, when: when ? when.slice(0, 10) : null, ref, confidence: 0.8 });
  }
  // 17. Payment received
  if (/\b(you(?:'ve| have)? received (?:a )?payment|sent you\s*(?:\$|USD|€|£)|paid you\s*(?:\$|USD|€|£)|received\s*(?:\$|USD|€|£)[\d,.]+\s+from|payment received|direct deposit (?:of|received)|credited to your account)\b/i.test(full) || /(收款成功|收到一笔转账|向您转账|已收款|收到付款|到账通知)/.test(full)) {
    const pm = full.match(/([A-Z][a-zA-Z0-9 '-]{1,40}?)\s+(?:sent|paid) you\b/i)
      || full.match(/received\s+[^\n]{1,30}?\s+from\s+([A-Z][a-zA-Z0-9 '-]{1,40}?)(?:\s+(?:on|for|via)\b|[.,!\n]|$)/i)
      || full.match(/(?:付款方|来自)[：:\s]*([^\n，,。]{2,30})/);
    const who = pm ? clean(pm[1]).replace(/[.,;:!]+$/, '') : fromName;
    return hit('payment_received', `Received${amtStr(amount)} from ${who}`, { who, amount, ref, confidence: 0.84 });
  }
  // 18. Payment sent
  if (!(/\breceipt\b/i.test(subject) && !/\b(you paid|you sent|payment sent)\b/i.test(full)) && (
    /\b(you (?:paid|sent)\s+|payment (?:sent|confirmed|successful)\b|paid\s*(?:\$|USD|€|£|¥)[\d,.]+\s+to\b|transfer (?:of|to)\s+[^\n]{1,40}(?:completed|sent|successful)|authorized a payment)\b/i.test(full)
    || /(付款成功|支付成功|转账成功|您已付款|扣款成功)/.test(full))) {
    const pm = full.match(/(?:you (?:paid|sent)\s+[^\n]{0,30}?\s+to|payment to)\s+([A-Z][a-zA-Z0-9 &'.,-]{1,45}?)(?:\s+(?:on|for|via|using|with)\b|[.,!\n]|$)/i)
      || full.match(/(?:收款方|商户|商家)[：:\s]*([^\n，,。]{2,30})/);
    const who = pm ? clean(pm[1]).replace(/[.,;:!]+$/, '') : fromName;
    return hit('payment_sent', `Paid${amtStr(amount)} to ${who}`, { who, amount, ref, confidence: 0.84 });
  }
  // 19. Order confirmation
  if (!/\b(order now|place your order|free shipping on orders)\b/i.test(full) && (/\b(order confirm(?:ed|ation)|thank(?:s| you) for your order|we(?:'ve| have)? received your order|your order\s+#[A-Z0-9-]+)\b/i.test(full) || /(订单确认|下单成功|已收到您的订单)/.test(full)))
    return hit('order', `Order from ${fromName}${amount ? ` (${fmtAmount(amount)})` : ''}`, { amount, ref, confidence: 0.8 });
  // 20. Receipt
  if (/\b(your receipt|payment receipt|purchase receipt|e-?receipt|receipt (?:from|for)|thank(?:s| you) for your (?:payment|purchase))\b/i.test(full) || /(电子收据|消费凭证|付款凭证|电子发票)/.test(full))
    return hit('receipt', `Receipt${amtStr(amount)} from ${fromName}`, { amount, ref, confidence: 0.8 });
  return [];
}

// --- Public API ---

export function extract(mail) {
  if (!mail || typeof mail !== 'object') return [];
  const jsonld = extractJsonLd(mail.html, mail);
  const structured = [...jsonld, ...(jsonld.length ? [] : extractMicrodata(mail.html, mail))];
  for (const item of extractIcs(mail)) {
    const dup = structured.some(s => (item.ref && s.ref && item.ref === s.ref) || (item.when && s.when && item.when.slice(0, 16) === s.when.slice(0, 16) && item.kind === s.kind));
    if (!dup) structured.push(item);
  }
  return structured.length ? structured : extractPattern(mail);
}

export function digestLine(item) {
  if (!item) return '';
  const parts = [];
  const w = formatShortWhen(item.when);
  if (w) parts.push(w);
  parts.push(`${item.kind}: ${clean(item.title)}`);
  const low = item.title.toLowerCase();
  if (item.who && item.who !== 'Unknown' && !low.includes(item.who.toLowerCase())) parts.push(`(${clean(item.who)})`);
  if (item.amount) {
    const a = fmtAmount(item.amount);
    if (a && !item.title.includes(a) && !item.title.includes(String(item.amount.value))) parts.push(a);
  }
  const wherePart = item.where && !low.includes(item.where.toLowerCase()) ? `@ ${clean(item.where)}` : '';
  const refPart = item.ref && !item.title.includes(item.ref) ? `ref ${clean(item.ref)}` : '';
  let line = [...parts, wherePart, refPart].filter(Boolean).join(' ');
  if (line.length > 120 && wherePart) line = [...parts, refPart].filter(Boolean).join(' ');
  return line.length > 120 ? `${line.slice(0, 117).trimEnd()}...` : line;
}
