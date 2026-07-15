// Reader-facing provenance banner for externally-derived pages (design D6).
// Renders only when frontmatter carries derived_from. Pure function of props —
// no fetching, so it is safe on public/anonymous views.
export default function DerivedProvenanceBanner({ metadata, lastModified }) {
  if (!metadata || !metadata.derived_from) return null;
  const sourceUrl = metadata.derived_source_url
    || (/^https?:\/\//.test(String(metadata.derived_from)) ? String(metadata.derived_from) : null);
  const sourceLabel = sourceUrl ? sourceUrl.replace(/^https?:\/\//, '') : String(metadata.derived_from);
  const connector = metadata.derived_connector;
  const orphaned = metadata.derived_orphaned === true || metadata.derived_orphaned === 'true';
  return (
    <div className="derived-banner" role="note" data-testid="derived-provenance-banner">
      <span className="derived-banner-icon" aria-hidden="true">↯</span>
      <span>
        Synced from {sourceUrl
          ? <a href={sourceUrl} target="_blank" rel="noopener noreferrer">{sourceLabel}</a>
          : <em>{sourceLabel}</em>}
        {lastModified && <> · last synced {new Date(lastModified).toLocaleDateString()}</>}
        {connector && <> · via connector <strong>{connector}</strong></>}
        {' · '}body is machine-managed
        {orphaned && <span className="derived-banner-orphaned"> — source no longer syncing</span>}
      </span>
    </div>
  );
}
