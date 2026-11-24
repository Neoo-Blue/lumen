// Speed: recognise the obvious requests before any model call, fetch their data at once and draw the card in about
// a second. The model then gets the result with the question, so it can answer in one step instead of two.
// Only confident matches; anything unclear is left to the model.
const TICKERS = { nvidia: 'NVDA', nvda: 'NVDA', 英伟达: 'NVDA', apple: 'AAPL', 苹果: 'AAPL', tesla: 'TSLA', 特斯拉: 'TSLA', microsoft: 'MSFT', 微软: 'MSFT',
  google: 'GOOGL', alphabet: 'GOOGL', 谷歌: 'GOOGL', amazon: 'AMZN', 亚马逊: 'AMZN', meta: 'META', netflix: 'NFLX', amd: 'AMD', intel: 'INTC', 英特尔: 'INTC',
  bitcoin: 'BTC-USD', btc: 'BTC-USD', 比特币: 'BTC-USD', ethereum: 'ETH-USD', eth: 'ETH-USD', 以太坊: 'ETH-USD', 's&p': '^GSPC', 标普: '^GSPC', nasdaq: '^IXIC', 纳斯达克: '^IXIC',
  tsmc: 'TSM', 台积电: 'TSM', alibaba: 'BABA', 阿里巴巴: 'BABA', tencent: '0700.HK', 腾讯: '0700.HK', robinhood: 'HOOD', palantir: 'PLTR', coinbase: 'COIN' };

const day = (offset = 0) => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + offset); return d.toISOString(); };
const clean = s => s.replace(/[?？!！.。,，]+$/g, '').replace(/\s+(lately|recently|now|today|these days|this week|right now)$/i, '').replace(/(最近|现在|今天)$/, '').trim();

// Questions that need judgment always go to the model, even when a card is prefetched.
// words that only say "news": with nothing else left, it is a request for today's top stories, not a topic search
const GENERIC_NEWS = /\b(?:can|could|would|will) you\b|\b(?:please|read|tell|give|show|me|us|what(?:'s| is| are)?|the|today'?s|today|this (?:morning|evening|week)|tonight|latest|top|stories|story|news|headlines?|breaking|any|some|in|on|about|for|world|happening|going on|right now|now|new|is|are)\b|新闻|头条|今天|最新|的|[?？.!,]/gi;
export const needsJudgment = text => /\b(buy|sell|trade|order|book|reserve|send|email|reply|remind|schedule|add|create|cancel|move|connect|check ?in|pay|transfer)\b|买|卖|下单|订|预订|发送|回复|提醒|安排|添加|取消|改到|连接|值机|付款|转账|\b(should|why|what does|mean|analy|compare|recommend|advice|versus|vs\.?|better|worth|predict|forecast|news|happened|going on|latest)\b|建议|要不要|为什么|分析|比较|怎么看|值得|预测|新闻|发生|怎么了/i.test(text);

// Several asks in one message, or anything asking Lumen to do something, goes straight to the model.
export const compound = text => /\b(and|plus|then|also|compare[ds]?|versus|vs|while|after that)\b|[,;，、；]|和|跟|顺便|还有|并且|然后|对比|以及/i.test(text)
  || /\b(build|make|create|switch|plan|set ?up|show me .* and|theme|profile|board|tracker|widget)\b|做一个|切换|主题|计划/i.test(text);

export function guess(text) {
  const t = text.trim(), low = t.toLowerCase();
  if (t.length > 90 || compound(t)) return [];
  const out = [];
  // stocks and crypto
  if (/stock|share|price|ticker|股价|股票|行情|涨|跌|how.*(doing|look)|怎么样/i.test(t)) {
    const syms = [...new Set(Object.entries(TICKERS).filter(([name]) => new RegExp(`(^|[^a-z])${name.replace(/[.*+?^${}()|[\]\\&]/g, '\\$&')}([^a-z]|$)`, 'i').test(low)).map(([, s]) => s))];
    if (syms.length === 1) out.push(['quote', { symbol: syms[0] }]); // several symbols: the model compares them
  }
  // weather
  let m = /weather (?:in|for|at) ([\p{L} .'-]{2,40})/iu.exec(t) || /([\p{L} .'-]{2,30}?)(?:'s)? weather/iu.exec(t) || /(?:^|在|去)?([\p{Script=Han}]{2,8}?)(?:的)?(?:天气|气温)/u.exec(t);
  if (m && !/my|today|tomorrow|今天|明天|这周|下周/i.test(m[1]) && !/^(the|a|an|this|that|our|your)\s|corner|way|morning|evening|outside|here|there/i.test(m[1].trim())) out.push(['weather', { city: clean(m[1]) }]);
  // people and things: who is X / what happened to X
  // live questions: search the web now, while the model reads the request (saves a whole step and most of the wait)
  if (!out.length && !/\b(my|our|mine|weather|forecast|calendar|emails?|inbox|texts?|messages?|photos?|pictures?|reminders?|alarm|schedule)\b|我的|天气|日历|短信|照片/i.test(t) && /\b(news|headlines?|top stories|latest|breaking|today'?s|this week|what happened|what'?s happening|what'?s going on|update on|why (?:did|was|is|are|were)|did .{2,30} (?:win|lose|die|resign|announce)|score|results? of|election|announced|released|just happened)\b|新闻|最新|头条|发生了什么|怎么了|为什么/i.test(t))
    out.push(['news', { query: clean(t).replace(/^(what(?:'s| is| are)? (?:the )?(?:latest|news|happening|going on)(?: news)?(?: on| with| about| in)?|what happened (?:to|with|in)|latest (?:news )?(?:on|about)|any news (?:on|about)|news (?:on|about)|tell me the news (?:on|about)|read me)\s+/i, '').replace(GENERIC_NEWS, ' ').replace(/\s+/g, ' ').trim(), lang: /[一-鿿]/.test(t) ? 'zh' : 'en' }]); // headlines in half a second; the model can still search the web
  m = out.some(([n]) => n === 'news') ? null : /^(?:who(?:'s| is| was)|tell me about)\s+(.{2,50})$/i.exec(clean(t)) || /^(.{2,20}?)(?:是谁|是什么人|怎么了|发生了什么|最近怎么样)$/u.exec(clean(t));
  if (m && !out.length && !needsJudgment(t)) out.push(['person', { name: clean(m[1]), lang: /[一-鿿]/.test(t) ? 'zh' : 'en' }]);
  // personal demo account
  return out.slice(0, 3);
}

// self-check: node prefetch.mjs
if (globalThis.process?.argv && import.meta.url === `file://${process.argv[1]}`) {
  const { strict: assert } = await import('node:assert');
  const name = q => guess(q).map(([n, i]) => n + ':' + Object.values(i)[0]).join(',');
  assert.equal(name('How is Nvidia stock doing?'), 'quote:NVDA');
  assert.equal(name('英伟达股价最近怎么样？'), 'quote:NVDA');
  assert.equal(name("What's the weather in Tokyo?"), 'weather:Tokyo');
  assert.equal(name('东京天气怎么样'), 'weather:东京');
  assert.equal(name('Who is Satya Nadella?'), 'person:Satya Nadella');
  assert.equal(name('What happened to Jensen Huang?'), 'news:Jensen Huang');
  assert.equal(name("Read me today's headlines"), 'news:');
  assert.equal(name("Can you read me today's headline?"), 'news:');
  assert.equal(name('top stories today'), 'news:');
  assert.equal(name('headlines in tech'), 'news:tech');
  assert.equal(name('any news on the election'), 'news:election');
  assert.equal(name('黄仁勋是谁？'), 'person:黄仁勋');
  assert.equal(name('Book me a flight to Boston'), '');
  assert.equal(name('build me a packing board for the tokyo trip, arrival-day weather in the corner'), '');
  assert.equal(name('How are AAPL, NVDA, and Bitcoin doing?'), '');
  assert.equal(name('Switch me to my work profile and show my unread emails'), '');
  assert.ok(needsJudgment('Buy 2 more shares of NVDA') && needsJudgment('帮我买两股英伟达') && !needsJudgment('How is Nvidia stock doing?'));
  assert.equal(name('What happened to Jensen Huang lately?'), 'news:Jensen Huang'); // news goes to a live search, not the encyclopedia
  assert.equal(name('Who is Jensen Huang now?'), 'person:Jensen Huang');
  console.log('prefetch ok');
}

// ---------- instant phone commands: run on the phone at once, no model at all ----------
const SWITCHES = { 'wi-?fi': 'Wi-Fi', 'bluetooth': 'Bluetooth', 'airplane mode|flight mode': 'Airplane mode', 'hotspot': 'Mobile Hotspot', 'location|gps': 'Location',
  'dark mode|night mode': 'Dark mode', 'power saving|battery saver|low power mode': 'Power saving', 'nfc': 'NFC', 'auto ?rotate|rotation': 'Auto rotate',
  'do not disturb|dnd': 'Do not disturb', '无线网|wifi': 'Wi-Fi', '蓝牙': 'Bluetooth', '飞行模式': 'Airplane mode', '深色模式|暗黑模式': 'Dark mode', '手电筒': 'Flashlight' };
export function phoneCommand(text, caps) {
  const t = String(text).trim().replace(/[.!?。！？]+$/, '').replace(/^(please |can you |could you |hey lumen,? )/i, '');
  if (/\b(and|then|after|before|but|if)\b|然后|并且|再/i.test(t) || t.length > 60) return null; // more than one thing: the model plans it
  const has = m => caps.includes(m);
  let m;
  if (has('calendarEvents') && /^(?:what'?s|what is|show(?: me)?)?\s*(?:on )?(?:my )?(?:calendar|schedule|agenda|day)(?: for)?(?: today)?$|^how does my day look$|^what do i have today$|^(?:我)?今天(?:的)?(?:安排|日程)(?:是什么)?$/i.test(t)) {
    const d0 = new Date(); d0.setHours(0, 0, 0, 0); const d1 = new Date(d0.getTime() + 864e5 - 1);
    return { method: 'calendarEvents', args: { from: d0.toISOString(), to: d1.toISOString(), show: 'today' }, say: '' };
  }
  if (has('openApp') && /^(?:scan(?: a)? (?:qr|code)?.*wechat|wechat scan|open wechat scan|用微信扫|微信扫一扫)/i.test(t)) return { method: 'openApp', args: { screen: 'wechat.scan' }, say: 'Opening the WeChat scanner.' };
  if (has('flashlight') && (m = t.match(/^(?:turn |switch )?(on|off) (?:the )?(?:flash ?light|torch)$|^(?:turn |switch )?(?:the )?(?:flash ?light|torch) (on|off)$|^(打开|关闭|关掉)手电筒$/i))) { const on = /on|打开/i.test(m[1] || m[2] || m[3]); return { method: 'flashlight', args: { on }, say: `Flashlight ${on ? 'on' : 'off'}.` }; }
  if (has('toggle')) for (const [re, name] of Object.entries(SWITCHES)) {
    const r = new RegExp(`^(?:turn |switch )?(on|off|enable|disable|打开|关闭|关掉) (?:the |my )?(?:${re})$|^(?:turn |switch )?(?:the |my )?(?:${re}) (on|off)$|^(打开|关闭|关掉)(?:${re})$`, 'i');
    if ((m = t.match(r))) { const on = /on|enable|打开/i.test(m[1] || m[2] || m[3]); return name === 'Do not disturb' && has('setting') ? { method: 'setting', args: { name: 'dnd', value: on ? 'on' : 'off' }, say: `Do not disturb ${on ? 'on' : 'off'}.` } : { method: 'toggle', args: { name, on }, say: `${name} ${on ? 'on' : 'off'}.` }; }
  }
  if (has('setting') && (m = t.match(/^(?:set |turn )?(?:the )?(volume|brightness)(?: to| at)? (\d{1,3})\s*%?$|^(volume|brightness) (up|down)$|^(mute|silent|unmute|vibrate)(?: mode)?$/i))) {
    if (m[5]) { const k = m[5].toLowerCase(); return k === 'vibrate' ? { method: 'setting', args: { name: 'vibrate', value: 'on' }, say: 'Vibrate.' } : { method: 'setting', args: { name: 'silent', value: k === 'unmute' ? 'off' : 'on' }, say: k === 'unmute' ? 'Sound on.' : 'Silent.' }; }
    if (m[1]) return { method: 'setting', args: { name: m[1].toLowerCase(), value: String(Math.min(100, +m[2])) }, say: `${m[1][0].toUpperCase() + m[1].slice(1).toLowerCase()} ${Math.min(100, +m[2])}%.` };
  }
  if (has('screenScroll') && (m = t.match(/^scroll(?: the screen| my screen| it| this)?(?: (up|down))?$|^(向上|向下)滚动$/i))) { const up = /up|向上/i.test(m[1] || m[2] || ''); return { method: 'screenScroll', args: { direction: up ? 'up' : 'down' }, say: '' }; }
  if (has('press') && (m = t.match(/^(?:go |press )?(back|home)$|^(?:go )?(?:to )?(?:the )?home ?screen$|^(?:show |open )(?:my )?(notifications|recent apps|recents)$|^(?:take a )?(screenshot)$|^lock(?: the)?(?: phone| screen)?$/i))) {
    const key = m[1] ? m[1].toLowerCase() : m[2] ? (/notif/i.test(m[2]) ? 'notifications' : 'recents') : m[3] ? 'screenshot' : /lock/i.test(t) ? 'lock' : 'home';
    return { method: 'press', args: { key }, say: '' };
  }
  if (has('openApp') && (m = t.match(/^(?:open|launch|start|go to|打开|启动)\s*(?:the\s+)?(.{2,30}?)(?:\s+app)?$/i)) && !/https?:|\.com|settings? for|tab|page|screen|\s(?:on|in|inside|within|from)\s|'s\s|\bmy\s/i.test(m[1]) && m[1].trim().split(/\s+/).length <= 3) return { method: 'openApp', args: { name: m[1] }, say: `Opening ${m[1]}.` };
  return null;
}

// A phone task that worked, turned into a skill the phone can replay without the model next time. Only plain
// navigation counts (open, tap by label, scroll, back/home, toggles): typing, taps by item number, and anything that
// sends, calls or needs a confirmation stay with the model, so a replay never repeats someone's words or a risky act.
export function skillFrom(text, acts) {
  // questions are answered by reading the screen, which a replay cannot do: those stay with the model
  if (/\?\s*$|^(what|how|is|are|does|do|did|check|when|where|who|which|why|can|could|tell|find out)\b|吗|什么|多少|怎么|哪/i.test(String(text).trim())) return null;
  const steps = [], ok = /^(openApp|screenTap|screenScroll|press|toggle|screenDo|wait)$/;
  const plain = s => s && typeof s === 'object' && Object.keys(s).length === 1 && (
    typeof s.tap === 'string' && s.tap || s.scroll === 'down' || s.scroll === 'up' || s.press === 'back' || s.press === 'home' ||
    Number.isFinite(s.wait) || typeof s.expect === 'string' || s.open && typeof s.open === 'object' && !s.open.link && (s.open.name || s.open.pkg)) || (s && typeof s.toggle === 'string' && Object.keys(s).every(k => k === 'toggle' || k === 'on'));
  let app = '';
  for (const { action, args = {}, ok: done } of acts) {
    if (!done || !ok.test(action) || /^skill/.test(action)) return null;
    if (action === 'openApp') { if ((!args.name && !args.pkg) || args.link) return null; app = app || String(args.name || args.pkg); steps.push({ open: Object.fromEntries(['name', 'pkg', 'screen'].filter(k => args[k]).map(k => [k, args[k]])) }); } // a link can carry a recipient or words
    else if (action === 'screenTap') { if (typeof args.text !== 'string' || !args.text) return null; steps.push({ tap: args.text }); }
    else if (action === 'screenScroll') steps.push({ scroll: args.direction === 'up' ? 'up' : 'down' });
    else if (action === 'press') { if (!/^(back|home)$/.test(args.key)) return null; steps.push({ press: args.key }); }
    else if (action === 'toggle') steps.push({ toggle: args.name, on: args.on });
    else if (action === 'screenDo') { if (Object.keys(args.params || {}).length || !Array.isArray(args.steps) || !args.steps.every(plain)) return null; steps.push(...args.steps); }
  }
  const name = String(text).trim().replace(/[?.!。！？]+$/, '').toLowerCase().slice(0, 60);
  if (steps.length < 2 || steps.length > 40 || !name || !steps[0].open) return null; // a skill starts by opening its app
  return { name, when: String(text).trim().slice(0, 200), app, params: [], steps, examples: [String(text).trim().slice(0, 160)] };
}

if (globalThis.process?.argv?.[1]?.endsWith("prefetch.mjs")) {
  const assert = await import('node:assert/strict');
  const all = ['openApp', 'flashlight', 'toggle', 'setting', 'screenScroll', 'press'];
  const pc = s => { const r = phoneCommand(s, all); return r ? r.method + ' ' + JSON.stringify(r.args) : null; };
  assert.equal(pc('Open WeChat'), 'openApp {"name":"WeChat"}');
  assert.equal(pc('Open Moments on WeChat'), null); // a place inside an app: the model or a learned skill
  assert.equal(pc('open my WeChat moments'), null);
  assert.equal(pc('open wechat scan'), 'openApp {"screen":"wechat.scan"}');
  assert.equal(pc('turn on wifi'), 'toggle {"name":"Wi-Fi","on":true}');
  assert.equal(pc('Bluetooth off'), 'toggle {"name":"Bluetooth","on":false}');
  assert.equal(pc('turn on the flashlight'), 'flashlight {"on":true}');
  assert.equal(pc('volume 30'), 'setting {"name":"volume","value":"30"}');
  assert.equal(pc('set brightness to 80%'), 'setting {"name":"brightness","value":"80"}');
  assert.equal(pc('Scroll my screen'), 'screenScroll {"direction":"down"}');
  assert.equal(pc('go back'), 'press {"key":"back"}');
  assert.equal(pc('打开蓝牙'), 'toggle {"name":"Bluetooth","on":true}');
  assert.equal(pc('open WeChat and message Li'), null);
  assert.equal(pc('open settings for notifications'), null);
  assert.equal(pc('what is the weather'), null);
  assert.match(phoneCommand("What's on my calendar today?", ['calendarEvents']).method, /calendarEvents/);
  assert.match(phoneCommand('How does my day look?', ['calendarEvents']).method, /calendarEvents/);

  const sk = skillFrom('Open my liked songs on Spotify.', [{ action: 'openApp', args: { name: 'Spotify' }, ok: true }, { action: 'screenDo', args: { steps: [{ tap: 'Your Library' }, { tap: 'Liked Songs' }] }, ok: true }]);
  assert.deepEqual(sk.steps, [{ open: { name: 'Spotify' } }, { tap: 'Your Library' }, { tap: 'Liked Songs' }]);
  assert.equal(sk.name, 'open my liked songs on spotify');
  assert.equal(skillFrom('text mom hi', [{ action: 'openApp', args: { name: 'Messages' }, ok: true }, { action: 'screenDo', args: { steps: [{ type: { text: 'hi' } }] }, ok: true }]), null); // typed words never replay
  assert.equal(skillFrom('x', [{ action: 'openApp', args: { name: 'A' }, ok: true }, { action: 'screenTap', args: { i: 4 }, ok: true }]), null); // item numbers change
  assert.equal(skillFrom('x', [{ action: 'openApp', args: { name: 'A' }, ok: true }, { action: 'smsSend', args: {}, ok: true }]), null);
  assert.equal(skillFrom('open spotify', [{ action: 'openApp', args: { name: 'Spotify' }, ok: true }]), null); // one step: nothing to learn
  assert.equal(skillFrom('x', [{ action: 'openApp', args: { name: 'A' }, ok: true }, { action: 'screenTap', args: { text: 'B' }, ok: false }]), null);
  const A = (st) => skillFrom('go there', [{ action: 'openApp', args: { name: 'A' }, ok: true }, { action: 'screenDo', args: { steps: st }, ok: true }]);
  assert.equal(A([{ tap: { i: 4 } }]), null); // item numbers change
  assert.equal(A([{ press: 'lock' }]), null);
  assert.equal(A([{ tap: 'A', type: 'x' }]), null);
  assert.ok(A([{ tap: 'Library' }, { scroll: 'down' }, { wait: 300 }]));
  assert.equal(skillFrom('x', [{ action: 'openApp', args: { name: 'WhatsApp', link: 'https://wa.me/1555?text=hi' }, ok: true }, { action: 'screenTap', args: { text: 'Send' }, ok: true }]), null);
  assert.equal(skillFrom('check my balance in Chase', [{ action: 'openApp', args: { name: 'Chase' }, ok: true }, { action: 'screenTap', args: { text: 'Accounts' }, ok: true }]), null);
  assert.equal(skillFrom('go', [{ action: 'screenTap', args: { text: 'A' }, ok: true }, { action: 'screenTap', args: { text: 'B' }, ok: true }]), null); // must open its app first
  assert.equal(skillFrom('go', [{ action: 'openApp', args: { name: 'A' }, ok: true }, { action: 'screenRead', args: {}, ok: true }, { action: 'screenTap', args: { text: 'B' }, ok: true }]), null);
  for (const q of ['Read my latest emails', 'Show my latest photos', 'What is on my calendar this week?', "What's today's weather?"]) assert.ok(!guess(q).some(([n]) => n === 'news'), q);
  console.log('phone commands ok');
}
