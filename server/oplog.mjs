// Operations log for improving Lumen: one JSON line per event in data/logs/YYYY-MM-DD.jsonl.
// Turns (what was asked, timings, which tools, failures), connect attempts, client-side errors, voice issues.
import { appendFile, mkdir, readFile, readdir } from 'node:fs/promises';

const dir = () => `${process.env.LUMEN_DATA || new URL('./data', import.meta.url).pathname}/logs`;
let ready;
export async function op(kind, fields) {
  ready ??= mkdir(dir(), { recursive: true, mode: 0o700 });
  await ready;
  const now = new Date().toISOString(); // the file and the time come from the server, never from fields
  const line = JSON.stringify({ ...fields, t: now, kind });
  await appendFile(`${dir()}/${now.slice(0, 10)}.jsonl`, line + '\n', { mode: 0o600 }).catch(() => {});
}

// Newest first; filter by kind or "problems" (anything with an error or a slow turn).
export async function recent({ days = 2, kind, problems, limit = 300 } = {}) {
  const files = (await readdir(dir()).catch(() => [])).filter(f => f.endsWith('.jsonl')).sort().slice(-days);
  const out = [];
  for (const f of files.reverse()) {
    const lines = (await readFile(`${dir()}/${f}`, 'utf8')).trim().split('\n').reverse();
    for (const l of lines) {
      if (!l) continue;
      const e = JSON.parse(l);
      if (kind && e.kind !== kind) continue;
      if (problems && !(e.error || e.errors?.length || e.ms > 30000 || e.ok === false)) continue;
      out.push(e);
      if (out.length >= limit) return out;
    }
  }
  return out;
}
