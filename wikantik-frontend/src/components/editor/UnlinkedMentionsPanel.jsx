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
    <ul className="mentions-list">
      {mentions.map((m) => (
        <li key={m.target} className="mention-row" data-testid="mention-row">
          <div className="mention-head">
            <span className="mention-phrase">“{m.phrase}”</span> → <span className="mention-title">{m.title}</span>
          </div>
          <button type="button" className="mention-context" onClick={() => onJump(m)}>
            line {m.line}: {m.context}
          </button>
          <div className="mention-actions">
            <button type="button" data-testid="mention-link" onClick={() => onLink(m)}>Link</button>
            <button type="button" data-testid="mention-ignore" onClick={() => onIgnore(m)}>Ignore</button>
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
