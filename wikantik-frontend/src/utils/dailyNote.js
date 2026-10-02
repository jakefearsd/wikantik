const pad = (n) => String(n).padStart(2, '0');

export function dailyNoteName(date = new Date()) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function dailyNoteTitle(date = new Date(), locale = undefined) {
  return date.toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
}

/** A new daily note: an ordinary article tagged daily-note (no page type or server change). */
export function buildDailyNote(date, { journal = false, locale } = {}) {
  const name = dailyNoteName(date);
  const title = dailyNoteTitle(date, locale);
  const initialMetadata = { type: 'article', date: name, tags: ['daily-note'], title };
  if (journal) initialMetadata.cluster = 'journal';
  return { name, initialMetadata, initialContent: `# ${title}\n\n` };
}

let inFlight = null;

/** Navigate (guarded) to today's note: open it if it exists, else start it as a new page saved on first save. */
export function openDailyNote(opts) {
  // A repeat invocation (double hotkey press) while the lookup is pending shares it instead of navigating twice.
  if (inFlight) return inFlight;
  inFlight = run(opts).finally(() => { inFlight = null; });
  return inFlight;
}

async function run({ api, go, now = new Date(), locale }) {
  const name = dailyNoteName(now);
  let exists = false;
  try {
    exists = ((await api.listPages({ names: [name], limit: 1 }))?.pages ?? []).some((p) => p.name === name);
  } catch (err) {
    console.warn('[daily-note] existence check failed for ' + name, err?.message || err);
  }
  if (exists) { go(`/edit/${name}`); return; }
  let journal = false;
  try {
    journal = ((await api.listClusters())?.clusters ?? []).some((c) => c.name === 'journal');
  } catch (err) {
    console.warn('[daily-note] cluster list unavailable', err?.message || err);
  }
  const { initialMetadata, initialContent } = buildDailyNote(now, { journal, locale });
  go(`/edit/${name}`, { state: { initialMetadata, initialContent } });
}
