export default function UnlinkedMentionsPanel({ status, mentions, onLink, onIgnore, onJump, onRetry }) {
  let body;
  if (status === 'warming') body = <p className="editor-rail-empty">Mentions available shortly</p>;
  else if (status === 'error') body = (
    <p className="editor-rail-empty">Couldn&apos;t scan for mentions{' '}
      <button type="button" className="link-button" data-testid="mention-retry" onClick={onRetry}>Retry</button></p>
  );
  else if (status === 'ok' && mentions.length === 0) body = <p className="editor-rail-empty">No unlinked mentions</p>;
  else if (mentions.length === 0) body = <p className="editor-rail-empty">Scanning…</p>;
  else body = (
    <ul className="mention-rows">
      {mentions.map((m) => (
        <li key={m.target} className="mention-row" data-testid="mention-row">
          <div className="mention-head">
            <span className="mention-phrase">“{m.phrase}”</span>{' '}
            {/* The non-breaking space keeps the arrow on the title's line; the span wraps as one unit. */}
            <span className="mention-target" data-testid="mention-target">→{' '}{m.title}</span>
          </div>
          <button type="button" className="mention-context" data-testid="mention-context" title={m.context}
                  aria-label={`Jump to line ${m.line}: ${m.context}`} onClick={() => onJump(m)}>
            <span className="mention-line" data-testid="mention-line">L{m.line}</span>
            <span className="mention-context-text">{m.context}</span>
          </button>
          <div className="mention-actions">
            <button type="button" className="mention-btn mention-btn-link" data-testid="mention-link"
                    onClick={() => onLink(m)}>Link</button>
            <button type="button" className="mention-btn mention-btn-ignore" data-testid="mention-ignore"
                    onClick={() => onIgnore(m)}>Ignore</button>
          </div>
        </li>
      ))}
    </ul>
  );
  return (
    <section className="editor-rail-section" data-testid="mentions-panel">
      <h4 className="editor-rail-heading">
        Unlinked mentions{mentions.length > 0 && <span className="editor-rail-badge">{mentions.length}</span>}
      </h4>
      {body}
    </section>
  );
}
