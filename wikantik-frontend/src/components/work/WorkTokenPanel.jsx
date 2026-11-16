import { useEffect, useRef, useState } from 'react';

/**
 * Shows an agent's plaintext key exactly once. The token lives only in the props of this panel: it is never
 * logged, stored or put in a message, and the caller drops it from its state when `onDone` fires.
 */
export default function WorkTokenPanel({ agentName, token, onDone }) {
  const [copied, setCopied] = useState(null); // null | true | false
  const panel = useRef(null);
  useEffect(() => { panel.current?.focus(); }, []);

  function copy() {
    // The only place the token leaves the page, and only on an explicit click.
    Promise.resolve(navigator.clipboard?.writeText(token))
      .then(() => setCopied(true))
      .catch(() => setCopied(false));
  }

  return (
    <section ref={panel} tabIndex={-1} className="work-token-panel" role="dialog" aria-modal="false"
      aria-label={`API key for ${agentName}`}>
      <h2 className="work-subheading">API key for {agentName}</h2>
      <p role="status" aria-live="polite">
        Copy this key now. It cannot be shown again; if you lose it, rotate the key to get a new one.
        {copied === true && ' Copied.'}
        {copied === false && ' Copying failed; select the key and copy it by hand.'}
      </p>
      <code className="work-token-value">{token}</code>
      <div className="work-actions">
        <button type="button" className="btn btn-sm" onClick={copy}>Copy</button>
        <button type="button" className="btn btn-sm btn-primary" onClick={onDone}>Done</button>
      </div>
    </section>
  );
}
