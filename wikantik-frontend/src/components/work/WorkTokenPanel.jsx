import { useEffect, useRef, useState } from 'react';

/**
 * Shows an agent's plaintext key exactly once. The token lives only in the props of this panel: it is never
 * logged, stored or put in a message, and the caller drops it from its state when `onDone` fires.
 */
export default function WorkTokenPanel({ agentName, token, onDone, autoFocus = true }) {
  const [copied, setCopied] = useState(null); // null | true | false
  const panel = useRef(null);
  // Only the first panel takes focus: a later one must not pull it from a panel the admin is already using.
  useEffect(() => { if (autoFocus) panel.current?.focus(); }, [autoFocus]);

  function copy() {
    // The only place the token leaves the page, and only on an explicit click. Only a resolved writeText is "Copied."
    let pending;
    try {
      pending = navigator.clipboard?.writeText ? navigator.clipboard.writeText(token) : null;
    } catch (err) {
      console.warn('WorkTokenPanel: clipboard write threw', err?.name);
      pending = null;
    }
    if (!pending || typeof pending.then !== 'function') { setCopied(false); return; }
    pending.then(() => setCopied(true)).catch((err) => {
      console.warn('WorkTokenPanel: clipboard write failed', err?.name);
      setCopied(false);
    });
  }

  return (
    <section ref={panel} tabIndex={-1} className="work-token-panel" role="dialog" aria-modal="false"
      aria-label={`API key for ${agentName}`}>
      <h2 className="work-subheading">API key for {agentName}</h2>
      <p role="status" aria-live="polite">
        Copy this key now. It cannot be shown again; if you lose it, rotate the key to get a new one.
        {copied === true && ' Copied.'}
        {copied === false && ' Copying failed \u2014 select the key and copy it by hand.'}
      </p>
      <code className="work-token-value">{token}</code>
      <div className="work-actions">
        <button type="button" className="btn btn-sm" onClick={copy}>Copy</button>
        <button type="button" className="btn btn-sm btn-primary" onClick={onDone}>Done</button>
      </div>
    </section>
  );
}
