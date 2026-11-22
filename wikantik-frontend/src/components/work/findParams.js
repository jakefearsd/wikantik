// The cross-project find filter as the browser holds it. The wire form is the query string (GET /api/work/items
// without project=) and, as a map of text, a saved filter's stored question: one parser on the server for both.
// People are named by display name (or the word "me", resolved by the server when the query runs).

/** Every find parameter but limit and cursor, in the order the canonical query writes them. */
export const FIELDS = [
  { name: 'projects', multi: true },
  { name: 'status', multi: true },
  { name: 'level', multi: true },
  { name: 'type', multi: false },
  { name: 'label', multi: true },
  { name: 'priority', multi: true },
  { name: 'assignee', multi: false },
  { name: 'queue', multi: false },
  { name: 'reporter', multi: false },
  { name: 'product', multi: false },
  { name: 'sprint', multi: false },
  { name: 'watching', multi: false },
  { name: 'q', multi: false },
  { name: 'dueBefore', multi: false },
  { name: 'updatedSince', multi: false },
  { name: 'sort', multi: false },
];

export const OPEN_STATUSES = ['draft', 'refining', 'ready', 'in_progress', 'review'];

/** The values of a multi field from text: split on commas and repeats, trimmed, blanks dropped. */
const list = (values) => values.flatMap((v) => String(v).split(',')).map((s) => s.trim()).filter(Boolean);

function readField(field, values) {
  if (field.name === 'watching') return values.some((v) => String(v).trim() === 'true') ? true : undefined;
  if (field.multi) {
    const items = list(values);
    return items.length ? items : undefined;
  }
  const text = values.map((v) => String(v).trim()).find(Boolean);
  return text || undefined;
}

function build(valuesOf) {
  const filter = {};
  for (const field of FIELDS) {
    const value = readField(field, valuesOf(field.name));
    if (value !== undefined) filter[field.name] = value;
  }
  return filter;
}

/** The filter held in URL search params; unknown parameters are ignored. */
export const fromQuery = (searchParams) => build((name) => searchParams.getAll(name));

/** A saved filter's stored map (name to text) as a filter. */
export const fromSaved = (map) => build((name) => (map && map[name] != null ? [map[name]] : []));

/** A filter's fields as text, in FIELDS order, empties left out; arrays comma-joined. */
function texts(filter) {
  const out = [];
  for (const field of FIELDS) {
    const raw = filter?.[field.name];
    let text = '';
    if (field.name === 'watching') text = raw === true || raw === 'true' ? 'true' : '';
    else if (Array.isArray(raw)) text = list(raw).join(',');
    else if (raw != null) text = String(raw).trim();
    if (text) out.push([field.name, text]);
  }
  return out;
}

/** The canonical query string, no leading "?". */
export const toQuery = (filter) => texts(filter)
  .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');

/** The saved form: { name: text }. */
export const toSaved = (filter) => Object.fromEntries(texts(filter));

export const BUILT_IN_VIEWS = [
  { id: 'assigned', label: 'Assigned to me', filter: { assignee: 'me', status: OPEN_STATUSES, sort: 'updated' } },
  { id: 'reported', label: 'Reported by me', filter: { reporter: 'me', status: OPEN_STATUSES, sort: 'updated' } },
  { id: 'watching', label: 'Watching', filter: { watching: true, status: OPEN_STATUSES, sort: 'updated' } },
];
