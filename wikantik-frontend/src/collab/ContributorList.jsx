import { displayNames } from './remoteCursors.js';

/** "Ana", "Ana and Raj", "Ana, Raj and Kim"; past `max` names, "Ana, Raj and 2 others". */
export function contributorText(names, max = 3) {
  const list = displayNames(names);
  if (!list.length) return '';
  const limit = Math.max(1, max);
  const shown = list.length > limit ? list.slice(0, limit - 1) : list;
  const rest = list.length > limit ? `${list.length - shown.length} others` : null;
  const parts = rest ? [...shown, rest] : shown;
  return parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`;
}

/** Who contributed to a draft, by display name only (never a login), with the full list in the tooltip. */
export default function ContributorList({ names, max = 3 }) {
  const list = displayNames(names);
  if (!list.length) return null;
  return <span className="collab-contributors" title={list.join(', ')}>{contributorText(list, max)}</span>;
}
