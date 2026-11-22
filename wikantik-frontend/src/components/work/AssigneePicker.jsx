import Select from '../ui/Select';
import { assignable } from './people';

const QUEUE = 'queue:';

/**
 * The one control for choosing who or what an item is for: a person (a project member, by display name) or an active
 * queue ("Queue · name"). Agents are never offered: agent work goes to a queue. An item has one target, so a choice is
 * reported as `{ assignee }` or `{ queue }`, never both. Choosing Unassigned clears whichever target the item has now.
 *
 * `allowNone={false}` drops Unassigned (a hand-off needs a target); a `placeholder` then labels the empty choice.
 * `value` is the current target `{ assignee?, queue? }`; `labels` carries the names to show when that target is no
 * longer on offer (a former member, a retired queue).
 */
export default function AssigneePicker({
  members = [], queues = [], value = {}, onChange, labels = {}, ariaLabel = 'Assignee', id, disabled = false, allowNone = true, placeholder,
}) {
  const current = value?.queue ? `${QUEUE}${value.queue}` : (value?.assignee || '');
  const options = [];
  if (allowNone || placeholder) options.push({ value: '', label: placeholder || 'Unassigned' });
  for (const m of assignable(members)) {
    if (m.principalKind === 'user' && m.displayName) options.push({ value: m.principal, label: m.displayName });
  }
  for (const q of queues) {
    if (!q.retiredAt) options.push({ value: `${QUEUE}${q.key}`, label: `Queue · ${q.name || q.key}` });
  }
  if (current && !options.some((o) => o.value === current)) {
    options.push(value?.queue
      ? { value: current, label: `Queue · ${labels.queueName || 'a queue'}` }
      : { value: current, label: labels.assigneeName || 'Former member' });
  }
  const choose = (v) => {
    if (v === '') {
      if (allowNone) onChange?.(value?.queue ? { queue: null } : { assignee: null });
    }
    else if (v.startsWith(QUEUE)) onChange?.({ queue: v.slice(QUEUE.length) });
    else onChange?.({ assignee: v });
  };
  return <Select id={id} ariaLabel={ariaLabel} value={current} options={options} disabled={disabled} onChange={choose} />;
}

/** The control string AssigneePicker uses for a target: a login, `queue:<key>`, or ''. */
export const targetValue = (t) => (t?.queue ? `${QUEUE}${t.queue}` : (t?.assignee || ''));
/** The inverse of {@link targetValue}: `{ queue }`, `{ assignee }`, or `{}` for none. */
export const targetOf = (v) => (!v ? {} : v.startsWith(QUEUE) ? { queue: v.slice(QUEUE.length) } : { assignee: v });
