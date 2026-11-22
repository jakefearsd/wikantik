import { useEffect, useState } from 'react';
import { apiUrl } from '../../api/client';
import { disableHook, enableHook } from '../../api/work';
import { useToast } from '../../hooks/useToast';
import { formatRelative } from '../../utils/datetime';
import WorkConfirm from './WorkConfirm';
import { SecretPanel } from './WorkWebhooks';

const SETUP = 'In GitHub: Settings → Webhooks → Add webhook; content type application/json; events: Pushes, Pull requests, Pull request reviews.';
const ASK = {
  on: (k) => `Turn on the code-host hook for ${k}? You will get an address and a secret to paste into GitHub.`,
  rotate: (k) => `Make a new secret for ${k}? The old secret stops working at once, so GitHub must be given the new one.`,
  off: (k) => `Turn off the code-host hook for ${k}? Deliveries from GitHub are refused until you turn it on again.`,
};
const YES = { on: 'Yes, turn on', rotate: 'Yes, make a new secret', off: 'Yes, turn off' };

/** Copies `text`; says when the browser refused. Never logs the text. */
function CopyButton({ text, label }) {
  const [state, setState] = useState(null);
  function copy() {
    let pending = null;
    try {
      pending = navigator.clipboard?.writeText ? navigator.clipboard.writeText(text) : null;
    } catch (err) {
      console.warn('WorkProductHook: clipboard write threw', err?.name);
    }
    if (!pending || typeof pending.then !== 'function') { setState('failed'); return; }
    pending.then(() => setState('copied')).catch((err) => {
      console.warn('WorkProductHook: clipboard write failed', err?.name);
      setState('failed');
    });
  }
  return (
    <>
      <button type="button" className="btn btn-sm" onClick={copy}>{label}</button>
      {state === 'copied' && <span className="work-muted" role="status"> Copied.</span>}
      {state === 'failed' && <span className="work-muted" role="status"> Copying failed — select the address and copy it by hand.</span>}
    </>
  );
}

/**
 * A product's code-host hook, for a wiki admin (the caller passes `hook` only when the server sent one). Turning on or
 * rotating shows the address and the secret once; the secret lives only in this component's state and is dropped by
 * Dismiss or by leaving the page. A failure is said in words and the page keeps what it had.
 */
export default function WorkProductHook({ productKey, hook, onChanged }) {
  const toast = useToast();
  const [asking, setAsking] = useState(null); // null | 'on' | 'rotate' | 'off'
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [reveal, setReveal] = useState(null); // { secret, url }
  useEffect(() => () => setReveal(null), []);

  function confirm() {
    if (busy) return;
    const kind = asking;
    setBusy(true); setError(null);
    (kind === 'off' ? disableHook(productKey) : enableHook(productKey))
      .then((reply) => {
        setAsking(null);
        if (kind === 'off') { setReveal(null); toast.success('Code-host hook turned off'); } else {
          // The server answers paths below the context path; the app's own base turns them into full addresses.
          const full = (path) => (path ? `${window.location.origin}${apiUrl(path)}` : null);
          setReveal({ secret: reply?.secret, github: full(reply?.githubUrl), generic: full(reply?.genericUrl) });
        }
        return onChanged?.();
      })
      .catch((e) => {
        console.warn('WorkProductHook: the hook change was refused', e?.status);
        setError(e?.message || 'Could not change the code-host hook.');
      })
      .finally(() => setBusy(false));
  }

  return (
    <div className="work-product-hook" data-testid={`hook-${productKey}`}>
      <div className="work-muted">
        Code host: {hook.enabled
          ? `on${hook.lastReceivedAt ? ` · last delivery ${formatRelative(hook.lastReceivedAt)}` : ' · nothing received yet'}`
          : 'off'}
      </div>
      {reveal?.secret && (
        <SecretPanel secret={reveal.secret} label="Code-host hook secret" onDone={() => setReveal(null)}>
          {reveal.github && (
            <p>
              GitHub address: <code className="work-token-value">{reveal.github}</code>{' '}
              <CopyButton text={reveal.github} label="Copy GitHub address" />
            </p>
          )}
          {reveal.generic && (
            <p>
              Generic address (signed JSON): <code className="work-token-value">{reveal.generic}</code>{' '}
              <CopyButton text={reveal.generic} label="Copy generic address" />
            </p>
          )}
          <p>Shown once. {SETUP}</p>
        </SecretPanel>
      )}
      {asking ? (
        <WorkConfirm yes={YES[asking]} disabled={busy} error={error} onYes={confirm}
          onNo={() => { setAsking(null); setError(null); }}>
          {ASK[asking](productKey)}
        </WorkConfirm>
      ) : (
        <div className="work-actions">
          {hook.enabled ? (
            <>
              <button type="button" className="btn btn-sm" aria-label={`New secret for ${productKey}`}
                onClick={() => { setError(null); setAsking('rotate'); }}>New secret</button>
              <button type="button" className="btn btn-sm btn-ghost" aria-label={`Turn off the hook for ${productKey}`}
                onClick={() => { setError(null); setAsking('off'); }}>Turn off</button>
            </>
          ) : (
            <button type="button" className="btn btn-sm" aria-label={`Turn on the hook for ${productKey}`}
              onClick={() => { setError(null); setAsking('on'); }}>Turn on</button>
          )}
        </div>
      )}
    </div>
  );
}
