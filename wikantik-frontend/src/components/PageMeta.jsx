import Badge from './ui/Badge';
import ClusterStatus from './ClusterStatus';
import { formatDate, formatRelative } from '../utils/datetime';
import { readingTime } from '../utils/readingTime';

/** Map confidence wire name → { variant, label } */
const CONFIDENCE_CHIP = {
  authoritative: { variant: 'success', label: 'Verified' },
  provisional:   { variant: 'default', label: 'Provisional' },
  stale:         { variant: 'warning', label: 'Stale' },
};

export default function PageMeta({ page }) {
  if (!page) return null;

  const date = page.lastModified ? formatDate(page.lastModified) : null;

  // Verification chip — driven by page.metadata.confidence + page.metadata.verified_at
  const confidence = page.metadata?.confidence;
  const chip = confidence ? CONFIDENCE_CHIP[confidence] : null;
  const verifiedAt = page.metadata?.verified_at;
  const chipTitle = chip && verifiedAt
    ? `Verified ${formatRelative(verifiedAt)}`
    : chip
    ? chip.label
    : undefined;

  // Taxonomy context. Everything past the bare chip is editor-only and client-side only,
  // so the anonymous render path stays exactly as it was — see ClusterStatus.
  const clusterStatus = page.cluster_status || null;
  const fmCluster = page.metadata?.cluster || null;
  const isHub = String(page.metadata?.type || '').toLowerCase() === 'hub';
  const canEdit = !!page.permissions?.edit;

  // Reading time — prefer page.content (raw markdown body from API) over contentHtml
  const textSource = page.content || page.contentHtml || '';
  const rt = readingTime(textSource);

  return (
    <div className="page-meta">
      {page.author && (
        <span className="page-meta-author">{page.author}</span>
      )}
      {date && (
        <>
          <span className="page-meta-dot">·</span>
          <span>{date}</span>
        </>
      )}
      {page.version > 0 && (
        <>
          <span className="page-meta-dot">·</span>
          <span>v{page.version}</span>
        </>
      )}
      {clusterStatus ? (
        <>
          <span className="page-meta-dot">·</span>
          <ClusterStatus clusterStatus={clusterStatus} isHub={isHub} canEdit={canEdit} />
        </>
      ) : fmCluster ? (
        // Legacy payload: the page names a cluster but the server sent no cluster_status
        // (older response, or a client holding a pre-Phase-1 payload). Show the bare chip
        // rather than inventing curation state we cannot actually verify.
        <>
          <span className="page-meta-dot">·</span>
          <span className="tag">{fmCluster}</span>
        </>
      ) : canEdit ? (
        <>
          <span className="page-meta-dot">·</span>
          <ClusterStatus clusterStatus={null} isHub={isHub} canEdit />
        </>
      ) : null}
      {rt.minutes > 0 && (
        <>
          <span className="page-meta-dot">·</span>
          <span className="page-meta-reading-time">{rt.minutes} min read</span>
        </>
      )}
      {chip && (
        <>
          <span className="page-meta-dot">·</span>
          <Badge variant={chip.variant} title={chipTitle}>{chip.label}</Badge>
        </>
      )}
    </div>
  );
}
