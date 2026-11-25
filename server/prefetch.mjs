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
  const has = m => caps.includes(m);
  // alarms, timers and music keys: instant (checked before the "more than one thing" rule, since "1 hour and 30 minutes" is one thing)
  { const c = String(text).trim().toLowerCase().replace(/[.!?。！？]+$/, '').replace(/^(please |can you |could you |hey lumen,? )/, '');
    const n = s => ({ a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, ten: 10, fifteen: 15, twenty: 20, thirty: 30, half: 0.5, 一: 1, 两: 2, 二: 2, 三: 3, 五: 5, 十: 10, 半: 0.5 })[s] ?? parseFloat(s);
    const unit = u => /^(h|小时|钟头)/.test(u) ? 3600 : /^(m|分)/.test(u) ? 60 : 1;
    let t;
    if (has('timer') && ((t = c.match(/^(?:set |start )?(?:a |an )?(?:timer|countdown)(?: for)? (\d+(?:\.\d+)?|a|an|one|two|three|five|ten|fifteen|twenty|thirty|half an?) ?(hours?|hrs?|minutes?|mins?|seconds?|secs?)(?:(?: and)? (\d+) ?(minutes?|mins?|seconds?|secs?))?$/)) ||
        (t = c.match(/^(\d+(?:\.\d+)?|a|an|one|two|three|five|ten|fifteen|twenty|thirty)[ -]?(hours?|hrs?|minutes?|mins?|seconds?|secs?)[ -]timer$/)) ||
        (t = c.match(/^(?:设|定|设置)?(?:一个)?(?:计时器?|倒计时)?(\d+|一|两|二|三|五|十|半)(?:个)?(小时|钟头|分钟|分|秒钟?|秒)(?:的)?(?:计时器?|倒计时|计时)?$/)) && /计时|倒计时/.test(c))) {
      const secs = Math.round(n(t[1].replace(/ an?$/, '')) * unit(t[2]) + (t[3] ? +t[3] * unit(t[4]) : 0));
      if (secs > 0 && secs <= 86400) return { method: 'timer', args: { seconds: secs, label: '' }, say: `Timer for ${secs >= 3600 ? +(secs / 3600).toFixed(2) + ' h' : secs >= 60 ? +(secs / 60).toFixed(1) + ' min' : secs + ' s'}.` };
    }
    if (has('alarm') && ((t = c.match(/^(?:set |make )?(?:an |a |my )?alarm (?:for |at )?(?:tomorrow )?(?:at )?(\d{1,2})(?::(\d{2}))? ?(am|pm|a\.m|p\.m)?(?: tomorrow)?$/)) ||
        (t = c.match(/^wake me(?: up)? (?:tomorrow )?(?:at )?(\d{1,2})(?::(\d{2}))? ?(am|pm|a\.m|p\.m)?(?: tomorrow)?$/)) ||
        (t = c.match(/^(\d{1,2})(?::(\d{2}))? ?(am|pm) alarm$/)))) {
      let h = +t[1]; const mi = +(t[2] || 0), ap = (t[3] || '')[0];
      if (ap === 'p' && h < 12) h += 12; if (ap === 'a' && h === 12) h = 0;
      if (h < 24 && mi < 60) return { method: 'alarm', args: { hour: h, minute: mi, label: '' }, say: `Alarm set for ${((h + 11) % 12) + 1}:${String(mi).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}.` };
    }
    if (has('alarm') && (t = c.match(/^(?:明天|今天)?(早上|上午|中午|下午|晚上)?(\d{1,2}|一|两|三|四|五|六|七|八|九|十|十一|十二)点(半|(\d{1,2})分?)?(?:的)?(?:闹钟|叫我(?:起床)?)$|^(?:定|设|设置)(?:一个)?(早上|上午|中午|下午|晚上)?(\d{1,2})点(半|(\d{1,2})分?)?的闹钟$/))) {
      const zh = { 一: 1, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10, 十一: 11, 十二: 12 };
      const part = t[1] || t[5], hh = t[2] || t[6], half = t[3] || t[7], mm = t[4] || t[8];
      let h = zh[hh] ?? +hh; const mi = half === '半' ? 30 : +(mm || 0);
      if (/下午|晚上/.test(part || '') && h < 12) h += 12; if (part === '中午' && h < 6) h += 12;
      if (h < 24 && mi < 60) return { method: 'alarm', args: { hour: h, minute: mi, label: '' }, say: `闹钟定在 ${h}:${String(mi).padStart(2, '0')}。` };
    }
    if (has('media')) {
      const act = /^(?:pause|pause (?:the |my )?(?:music|song|video|podcast)|stop (?:the |my )?(?:music|song|playing)|暂停|暂停音乐|停止播放)$/.test(c) ? 'pause'
        : /^(?:resume|resume (?:the )?(?:music|song)|continue playing|unpause|keep playing|继续播放|继续)$/.test(c) ? 'play'
        : /^(?:next|next (?:song|track)|skip|skip (?:this )?(?:song|track)|play the next song|下一首|下一曲|切歌)$/.test(c) ? 'next'
        : /^(?:previous|previous (?:song|track)|last song|go back a song|play the previous song|上一首|上一曲)$/.test(c) ? 'previous' : '';
      if (act) return { method: 'media', args: { action: act }, say: '' };
    }
  }
  if (/\b(and|then|after|before|but|if)\b|然后|并且|再/i.test(t) || t.length > 60) return null; // more than one thing: the model plans it
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

// Starter skills (skills-pack.json): everyday requests that open the right app screen at once, no model.
// A phrase is the person's words with at most one {q} (a search term, place, song...); only skills whose app is
// installed (pkg) are tried. Returns { skill, q } or null.
const FILLER = /^(?:hey lumen[,\s]*|please\s+|can you\s+|could you\s+|would you\s+|i want to\s+|i'd like to\s+|let's\s+|请|帮我|给我)+|(?:\s+please|\s+for me|\s+now|一下|吧)+$/gi;
const norm = s => String(s).replace(/[’']/g, "'").replace(/[.!?。！？,，]+$/g, '').replace(/\s+/g, ' ').trim().replace(FILLER, '').trim();
// people say the same thing many ways: one verb for opening, and no "my", "the" or a trailing "settings"/"page"/"screen"
const canon = s => s.replace(/^(?:open up|open|show me|show|check|bring up|pull up|view|see|launch|display|打开|显示|查看)\s*/i, 'open ')
  .replace(/\b(?:my|the|me)\s+/gi, '').replace(/\s+(?:settings?|page|screen|menu|app)$/i, '').replace(/\s+/g, ' ').trim();
const packRx = new Map();
export function packMatch(text, pack, installed) {
  const said = canon(norm(text)); if (!said || said.length > 120) return null;
  const has = installed && new Set(installed);
  let best = null, lit = -1; // a phrase with no {q} wins outright; otherwise the pattern with the most fixed words
  for (const skill of pack?.skills || []) {
    if (skill.pkg && has && !has.has(skill.pkg)) continue;
    for (const ph of skill.phrases || []) {
      let rx = packRx.get(ph);
      if (!rx) { const c = canon(norm(ph)), body = c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\\\{q\\\}/, '(.+?)'); rx = new RegExp(`^${body}$`, 'i'); rx.lit = c.replace('{q}', '').length; packRx.set(ph, rx); }
      const m = rx.exec(said); if (!m) continue;
      if (m[1] === undefined) return { skill, q: null };
      const q = m[1].trim(), l = rx.lit;
      if (q && q.length <= 80 && l > lit) { best = { skill, q }; lit = l; }
    }
  }
  return best;
}
// the steps to run: {q} goes into links URL-encoded, and as typed text elsewhere
export const packSteps = (skill, q) => JSON.parse(JSON.stringify(skill.steps), (k, v) => typeof v === 'string' && q != null ? v.replace(/\{q\}/g, k === 'link' ? encodeURIComponent(q) : q) : v);

// Voice shortcuts: the person's own phrase runs a saved list of steps on the phone, no model in between. Only these
// step kinds exist; anything that sends, pays or calls is left out (a text opens prefilled for the person to send), and
// a part that needs judgment is an "ask" step, run as an ordinary request when the steps are done.
export const SHORTCUT_STEPS = { openApp: ['name', 'pkg', 'link'], navigate: ['destination', 'mode'], playSearch: ['q', 'pkg'], media: ['action'], toggle: ['name', 'on'],
  setting: ['name', 'value'], flashlight: ['on'], alarm: ['hour', 'minute', 'label'], timer: ['seconds', 'label'], systemScreen: ['action', 'category', 'pkg'],
  share: ['pkg', 'text'], sms: ['number', 'text'], skillRun: ['name'], press: ['key'] };
export function cleanShortcut(sc) {
  const name = String(sc?.name || '').trim().slice(0, 60);
  const phrases = [...new Set([name, ...(Array.isArray(sc?.phrases) ? sc.phrases : [])].map(p => String(p || '').trim().slice(0, 80)).filter(p => p.length > 1))].slice(0, 8);
  if (!name || !phrases.length) return { error: 'a shortcut needs a name and the words that start it' };
  const steps = [];
  for (const s of Array.isArray(sc.steps) ? sc.steps.slice(0, 20) : []) {
    if (s && typeof s.say === 'string') steps.push({ say: s.say.slice(0, 200) });
    else if (s && Number.isFinite(+s.wait)) steps.push({ wait: Math.min(Math.max(+s.wait, 100), 10000) });
    else if (s && typeof s.ask === 'string' && s.ask.trim()) steps.push({ ask: s.ask.trim().slice(0, 400) });
    else if (s && SHORTCUT_STEPS[s.do]) {
      const args = Object.fromEntries(SHORTCUT_STEPS[s.do].filter(k => s.args?.[k] !== undefined && s.args[k] !== '').map(k => [k, typeof s.args[k] === 'string' ? s.args[k].slice(0, 300) : s.args[k]]));
      if (s.do === 'share' && args.pkg !== 'com.teslamotors.tesla') return { error: 'sharing goes only to the car' };
      if (s.do === 'press' && !/^(home|back)$/.test(args.key)) return { error: 'only home or back' };
      if (s.do === 'openApp' && args.link && !/^(https:|tel:|smsto:|mailto:|geo:|google\.navigation:|spotify:|[a-z][\w+.-]*:\/\/)/i.test(args.link)) return { error: 'that link cannot be opened' };
      steps.push({ do: s.do, args });
    } else return { error: `unknown step: ${JSON.stringify(s).slice(0, 80)}` };
  }
  if (!steps.length) return { error: 'a shortcut needs at least one step' };
  return { name, phrases, steps };
}
export const describeStep = s => s.say ? `Say "${s.say}"` : s.wait ? `Wait ${Math.round(s.wait / 100) / 10} s` : s.ask ? `Ask Lumen: ${s.ask}`
  : ({ openApp: a => `Open ${a.name || a.pkg || a.link}`, navigate: a => `Directions to ${a.destination}`, playSearch: a => `Play ${a.q}`, media: a => `Music: ${a.action}`,
    toggle: a => `Turn ${a.on === false ? 'off' : 'on'} ${a.name}`, setting: a => ({ dnd: `Do Not Disturb ${a.value}`, silent: `Silent ${a.value}`, vibrate: `Vibrate ${a.value}`, autoRotate: `Auto rotate ${a.value}` })[a.name] || `${({ volume: 'Volume', ringVolume: 'Ring volume', alarmVolume: 'Alarm volume', brightness: 'Brightness', screenTimeout: 'Screen timeout' })[a.name] || a.name} to ${a.value}${/olume|brightness/i.test(a.name) ? '%' : ''}`, flashlight: a => `Flashlight ${a.on === false ? 'off' : 'on'}`,
    alarm: a => `Alarm at ${a.hour}:${String(a.minute ?? 0).padStart(2, '0')}`, timer: a => `Timer for ${Math.round(a.seconds / 60)} min`, systemScreen: a => `Open ${(a.action || a.category || '').split('.').pop().toLowerCase().replace(/_/g, ' ')}`,
    share: a => `Send "${a.text}" to the car`, sms: a => `Write a text to ${a.number}: "${a.text}" (you send it)`, skillRun: a => `Run the skill "${a.name}"`, press: a => `Go ${a.key}` })[s.do](s.args || {});
/** The person's shortcut for these words, if any: the same words, or the same once filler is set aside. */
export function shortcutMatch(text, list) {
  const said = canon(norm(text)).toLowerCase(); if (!said) return null;
  for (const sc of list || []) for (const ph of sc.phrases || []) if (canon(norm(ph)).toLowerCase() === said) return sc;
  return null;
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
  const PK = { skills: [
    { id: 'yt.search', pkg: 'com.google.android.youtube', phrases: ['search youtube for {q}', '在youtube上搜{q}'], steps: [{ open: { pkg: 'com.google.android.youtube', link: 'https://www.youtube.com/results?search_query={q}' } }] },
    { id: 'yt.subs', pkg: 'com.google.android.youtube', phrases: ['open my youtube subscriptions'], steps: [{ open: { link: 'https://www.youtube.com/feed/subscriptions' } }] },
    { id: 'maps.nav', pkg: null, phrases: ['navigate to {q}'], steps: [{ open: { link: 'google.navigation:q={q}' } }] },
  ] };
  const pm = (t, inst = ['com.google.android.youtube']) => packMatch(t, PK, inst);
  assert.equal(pm('Search YouTube for lofi beats please')?.q, 'lofi beats');
  assert.equal(pm('can you open youtube subscriptions?')?.skill.id, 'yt.subs');
  assert.equal(pm('在YouTube上搜周杰伦')?.q, '周杰伦');
  assert.equal(pm('search youtube for cats', [])?.skill.id, undefined); // app not installed
  assert.equal(pm('navigate to SFO', [])?.q, 'SFO');
  assert.equal(pm('what is youtube'), null);
  assert.equal(packSteps(PK.skills[0], 'lofi & chill')[0].open.link, 'https://www.youtube.com/results?search_query=lofi%20%26%20chill');
  const ck = s => { const r = phoneCommand(s, ['timer', 'alarm', 'media']); return r ? r.method + ' ' + JSON.stringify(r.args) : null; };
  assert.equal(ck('Set a timer for 10 minutes'), 'timer {"seconds":600,"label":""}');
  assert.equal(ck('timer for 1 hour and 30 minutes'), 'timer {"seconds":5400,"label":""}');
  assert.equal(ck('5 minute timer'), 'timer {"seconds":300,"label":""}');
  assert.equal(ck('set a timer for half an hour'), 'timer {"seconds":1800,"label":""}');
  assert.equal(ck('计时5分钟'), 'timer {"seconds":300,"label":""}');
  assert.equal(ck('Set an alarm for 6:30 am'), 'alarm {"hour":6,"minute":30,"label":""}');
  assert.equal(ck('wake me up at 7pm'), 'alarm {"hour":19,"minute":0,"label":""}');
  assert.equal(ck('alarm for 12 am'), 'alarm {"hour":0,"minute":0,"label":""}');
  assert.equal(ck('明天早上7点叫我'), 'alarm {"hour":7,"minute":0,"label":""}');
  assert.equal(ck('定一个下午3点半的闹钟'), 'alarm {"hour":15,"minute":30,"label":""}');
  assert.equal(ck('next song'), 'media {"action":"next"}');
  assert.equal(ck('Pause the music'), 'media {"action":"pause"}');
  assert.equal(ck('暂停'), 'media {"action":"pause"}');
  assert.equal(ck('set an alarm to remind me to call mom'), null);
  assert.equal(pm('show me my youtube subscriptions')?.skill.id, 'yt.subs');
  assert.equal(pm('open youtube subscriptions page')?.skill.id, 'yt.subs');
  const sc = cleanShortcut({ name: 'Heading home', phrases: ["I'm heading home"], steps: [{ do: 'navigate', args: { destination: 'home' } }, { do: 'playSearch', args: { q: 'driving mix', pkg: 'com.google.android.apps.youtube.music' } }, { do: 'sms', args: { number: 'Mom', text: 'On my way' } }, { ask: 'anything I should pick up?' }] });
  assert.equal(sc.steps.length, 4);
  assert.equal(shortcutMatch('heading home', [sc])?.name, 'Heading home');
  assert.equal(shortcutMatch("I'm heading home.", [sc])?.name, 'Heading home');
  assert.equal(shortcutMatch('heading to work', [sc]), null);
  assert.match(cleanShortcut({ name: 'test me', steps: [{ do: 'call', args: { number: '1' } }] }).error, /unknown step/);
  assert.match(cleanShortcut({ name: 'test me', steps: [{ do: 'share', args: { pkg: 'com.whatsapp', text: 'hi' } }] }).error, /car/);
  assert.match(cleanShortcut({ name: 'test me', steps: [{ do: 'openApp', args: { link: 'javascript:alert(1)' } }] }).error, /cannot be opened/);
  assert.equal(describeStep(sc.steps[2]), 'Write a text to Mom: "On my way" (you send it)');
  console.log('phone commands ok');
}
