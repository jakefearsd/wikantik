import Badge from './ui/Badge';
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
      {page.metadata?.cluster && (
        <>
          <span className="page-meta-dot">·</span>
          <span className="tag">{page.metadata.cluster}</span>
        </>
      )}
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
