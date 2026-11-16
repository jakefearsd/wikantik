import { useEffect, useId, useRef, useState } from 'react';
import { clockText, fullTime, isPast, useExpiryClock } from './workTime';

const CLAIMABLE = new Set(['ready', 'review']);

/**
 * Who holds the item, since when and until when, in words (not colour alone). A person may release an agent's claim
 * to unstick it: that asks first, takes an optional note and says where the item goes. Unclaimed work in a queue
 * that agents could take says so. `onRelease(note)` resolves true once released. `now` is injectable for tests.
 */
export default function WorkClaim({ item, canRelease = false, onRelease, now: injectedNow }) {
  const now = useExpiryClock(item.claimExpiresAt, injectedNow);
  const uid = useId();
  const [confirming, setConfirming] = useState(false);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const noteRef = useRef(null);
  const openRef = useRef(null);
  const refocus = useRef(false);

  useEffect(() => {
    if (confirming) noteRef.current?.focus();
    else if (refocus.current) { refocus.current = false; openRef.current?.focus(); }
  }, [confirming]);

  const claimed = item.claimedByAgent != null;
  if (!claimed) {
    if (item.queue && CLAIMABLE.has(item.status)) {
      return (
        <section className="work-claim work-claim-available" aria-label="Claim">
          <p>Claimable by agents serving {item.queueName || 'this queue'}</p>
        </section>
      );
    }
    return null;
  }

  const holder = item.claimedByName || 'an agent';
  const lapsed = isPast(item.claimExpiresAt, now);
  const close = () => { refocus.current = true; setConfirming(false); };
  const where = item.queue ? `back to the ${item.queueName || 'its'} queue` : 'unclaimed';

  function release() {
    if (busy) return;
    setBusy(true);
    Promise.resolve(onRelease(note.trim()))
      .then((done) => { if (done) { setNote(''); close(); } })
      .catch((e) => console.warn('WorkClaim: release failed', e))
      .finally(() => setBusy(false));
  }

  return (
    <section className={`work-claim${lapsed ? ' claim-expired' : ''}`} aria-label="Claim">
      <p className="work-claim-line">
        <strong>Claimed by {holder}</strong>
        <span className="work-claim-tag">Agent</span>
      </p>
      <p className="work-claim-times">
        <span title={fullTime(item.claimedAt)}>since {clockText(item.claimedAt, now)}</span>
        {' · '}
        <span title={fullTime(item.claimExpiresAt)}>
          {lapsed ? `claim lapsed at ${clockText(item.claimExpiresAt, now)}` : `until ${clockText(item.claimExpiresAt, now)}`}
        </span>
      </p>
      {canRelease && !confirming && (
        <button ref={openRef} type="button" className="btn btn-secondary btn-sm" onClick={() => setConfirming(true)}>Release claim</button>
      )}
      {canRelease && confirming && (
        <div className="work-confirm" role="alertdialog" aria-label="Release claim"
          onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); close(); } }}>
          <p>Release {holder}'s claim? The item goes {where}.</p>
          <label className="work-form-field" htmlFor={`${uid}-note`}>Release note (optional)</label>
          <textarea ref={noteRef} id={`${uid}-note`} className="work-form-body" rows={2} value={note} disabled={busy}
            onChange={(e) => setNote(e.target.value)} />
          <div className="work-actions">
            <button type="button" className="btn btn-sm btn-danger" disabled={busy} onClick={release}>Release it</button>
            <button type="button" className="btn btn-sm btn-ghost" disabled={busy} onClick={close}>Cancel</button>
          </div>
        </div>
      )}
    </section>
  );
}
