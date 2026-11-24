// What the person chose in the setup dashboard, kept in the data folder (owner-only file).
// Environment variables win, so Docker users can also configure everything in .env.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { makeBrain } from './brain.mjs';

export const DATA = process.env.LUMEN_DATA || './data';
mkdirSync(DATA, { recursive: true, mode: 0o700 });
const FILE = `${DATA}/config.json`;

const DEFAULTS = { name: 'Lumen', provider: 'anthropic', apiKey: '', baseUrl: '', model: '', cli: 'claude', voiceProvider: 'cartesia', voiceKey: '', voiceSecret: '', voiceUrl: '', voiceId: '', setupDone: false, allowAll: true, allowed: [] };
const ENV = { name: process.env.LUMEN_NAME, provider: process.env.LUMEN_PROVIDER, apiKey: process.env.LUMEN_API_KEY || process.env.ANTHROPIC_API_KEY,
  baseUrl: process.env.LUMEN_BASE_URL, model: process.env.LUMEN_MODEL, cli: process.env.LUMEN_CLI,
  voiceProvider: process.env.LUMEN_VOICE_PROVIDER, voiceKey: process.env.LUMEN_VOICE_KEY || process.env.CARTESIA_API_KEY, voiceSecret: process.env.LUMEN_VOICE_SECRET, voiceUrl: process.env.LUMEN_VOICE_URL, voiceId: process.env.LUMEN_VOICE };

let saved = {}; // only what was set in the dashboard is written back; values from the environment stay out of the file
try { saved = JSON.parse(readFileSync(FILE, 'utf8')); } catch {}
const merge = () => { const c = { ...DEFAULTS, ...saved }; for (const [k, v] of Object.entries(ENV)) if (v) c[k] = v; if (process.env.LUMEN_SETUP_DONE === '1') c.setupDone = true; return c; };
let cfg = merge();

let brainNow = null;
export const config = () => cfg;
export function saveConfig(patch) {
  saved = { ...saved, ...patch }; cfg = merge(); brainNow = null;
  writeFileSync(FILE, JSON.stringify(saved, null, 2), { mode: 0o600 });
  return cfg;
}
/** The configured model; throws a plain sentence when setup is not finished. */
export function brain() {
  if (!cfg.apiKey && !['ollama', 'custom', 'cli'].includes(cfg.provider)) throw new Error('Lumen has no AI model yet: finish setup in the dashboard');
  return (brainNow ||= makeBrain(cfg));
}
/** Settings safe to show in the dashboard: keys are reduced to their last four characters. */
export const publicConfig = () => ({ ...cfg, apiKey: cfg.apiKey ? '••••' + cfg.apiKey.slice(-4) : '', voiceKey: cfg.voiceKey ? '••••' + cfg.voiceKey.slice(-4) : '', voiceSecret: cfg.voiceSecret ? '••••' : '', callKey: cfg.callKey ? '••••' : '' });
