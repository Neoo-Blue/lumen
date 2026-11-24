// Starting point when the server runs inside the Android app (phone mode). The app writes the settings to a JSON
// file (mode, this install's secret, the data folder, the port) and passes its path; then the normal server starts.
import { readFileSync } from 'node:fs';

const file = process.argv[2] || process.env.LUMEN_SETTINGS; // the app passes it either way
if (!file) throw new Error('phone.mjs needs the path of its settings file');
const env = JSON.parse(readFileSync(file, 'utf8'));
for (const [k, v] of Object.entries(env)) if (typeof v === 'string') process.env[k] = v;
process.env.LUMEN_MODE = 'phone';
process.on('uncaughtException', e => console.error('lumen: uncaught', e?.stack || e)); // one bad request must not end the app's process
process.on('unhandledRejection', e => console.error('lumen: unhandled', e?.stack || e));
await import('./server.mjs');
