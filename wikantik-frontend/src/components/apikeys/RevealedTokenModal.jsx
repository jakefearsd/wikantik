import { useState } from 'react';

function RevealedTokenModal({ token, record, onClose }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(token);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content admin-modal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ fontFamily: 'var(--font-display)', marginBottom: 'var(--space-md)' }}>
          Copy this token now
        </h2>
        <p>
          This is the only time you will see the plaintext key for{' '}
          <strong>{record.principalLogin}</strong>
          {record.label ? <> (<em>{record.label}</em>)</> : null}. Store it somewhere safe —
          after closing this dialog only the hash remains.
        </p>
        <div style={{
          marginTop: 'var(--space-md)',
          padding: 'var(--space-md)',
          background: 'var(--color-surface-alt, #f5f5f5)',
          border: '1px solid var(--color-border, #ccc)',
          borderRadius: '4px',
          fontFamily: 'monospace',
          fontSize: '0.9em',
          wordBreak: 'break-all',
        }}>
          {token}
        </div>
        <div className="modal-actions" style={{ marginTop: 'var(--space-lg)' }}>
          <button className="btn btn-ghost" onClick={copy}>
            {copied ? 'Copied ✓' : 'Copy to clipboard'}
          </button>
          <button className="btn btn-primary" onClick={onClose}>
            I've saved it — close
          </button>
        </div>
      </div>
    </div>
  );
}

export default RevealedTokenModal;
