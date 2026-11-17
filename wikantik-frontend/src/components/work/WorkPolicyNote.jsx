import { useCallback, useEffect, useState } from 'react';
import { acknowledgeItem, getItemPolicy } from '../../api/work';

const LEVEL = { supervised: 'Supervised', delegated: 'Delegated', autonomous: 'Autonomous' };
const LEVEL_MEANS = {
  supervised: 'a person approves, merges and closes',
  delegated: 'a person approves; an agent may review, merge what it approved, and close',
  autonomous: 'agents may approve, review, merge and close; a person acknowledges each close',
};

/** Where the effective level comes from, as far as the item's own setting tells. */
function source(own, effective) {
  if (!own) return 'inherited';
  if (own === effective) return 'set on this item';
  return `this item says ${LEVEL[own] || own}; the product's ceiling caps it`;
}

/**
 * What governs the item right now: the effective autonomy level (after its epic, its project and its product's
 * ceiling), direct commits, when it counts as done, and whether it waits for a person's acknowledgement.
 */
export default function WorkPolicyNote({ itemKey, ownAutonomy = null, canAcknowledge = false, refreshKey = 0, onChanged }) {
  const [policy, setPolicy] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const [failed, setFailed] = useState(false);

  const load = useCallback(() => getItemPolicy(itemKey)
    .then((p) => { setPolicy(p); setFailed(false); })
    .catch((e) => {
      console.warn('WorkPolicyNote: could not load the policy', e);
      setFailed(true);
    }), [itemKey]);

  useEffect(() => { load(); }, [load, refreshKey]);

  if (failed && !policy?.effective) {
    return (
      <p className="work-muted">
        Could not load the autonomy policy.{' '}
        <button type="button" className="btn btn-ghost btn-sm" onClick={load}>Retry</button>
      </p>
    );
  }
  if (!policy?.effective) return null;
  const { autonomy, allowDirect, doneMeans } = policy.effective;

  function acknowledge() {
    setBusy(true); setError(null);
    acknowledgeItem(itemKey)
      .then(() => { onChanged?.(); return load(); })
      .catch((e) => setError(e?.message || 'Could not acknowledge this.'))
      .finally(() => setBusy(false));
  }

  return (
    <div className="work-policy-note" data-testid="policy-note">
      <p className="work-muted">
        Effective level: <strong>{LEVEL[autonomy] || autonomy}</strong> ({source(ownAutonomy, autonomy)}):{' '}
        {LEVEL_MEANS[autonomy]}. Direct commits by agents are {allowDirect ? 'allowed' : 'not allowed'}.{' '}
        Done means {doneMeans === 'in_production' ? 'running in production' : 'merged'}.
      </p>
      {policy.ackWaiting && (
        <div className="work-ack" role="status">
          An agent closed this with no person in the path.
          {canAcknowledge && (
            <button type="button" className="btn btn-sm" disabled={busy} onClick={acknowledge}>Acknowledge</button>
          )}
        </div>
      )}
      {policy.ackRequired && !policy.ackWaiting && (
        <p className="work-muted">
          Closed by an agent with no person in the path; acknowledged by {policy.acknowledgedByName || 'someone'}
          {policy.acknowledgedAt ? ` on ${new Date(policy.acknowledgedAt).toLocaleDateString()}` : ''}.
        </p>
      )}
      {error && <div className="work-form-error" role="alert">{error}</div>}
    </div>
  );
}
