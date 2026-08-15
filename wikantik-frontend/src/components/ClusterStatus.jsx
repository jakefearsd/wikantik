import { Link } from 'react-router-dom';
import Badge from './ui/Badge';

/**
 * Taxonomy context for a page, rendered from the server-derived `cluster_status`
 * block on the page payload.
 *
 * A cluster is declared by the unique page carrying `type: hub` plus that cluster path
 * (ClusterDeclarationDesign). That pairing is implicit in frontmatter, so a hub is stated
 * explicitly here rather than left for the reader to infer.
 *
 * Everything beyond the plain chip is gated on `canEdit`, client-side only. The anonymous
 * render path is deliberately byte-identical to what it was before: curation state is not a
 * signal a search crawler should see, and leaving it out keeps SEO tuning unconfounded by
 * this feature. Gating here rather than in SSR also means the server response never varies
 * by user, so edge caching is unaffected.
 *
 * `hub_slug` is absent from the payload when nothing declares the cluster (the server omits
 * null keys), so declaration is read from the `hub_declared` boolean, which is always present.
 */
export default function ClusterStatus({ clusterStatus, isHub = false, canEdit = false }) {
  const path = clusterStatus?.path || null;

  // Readers see exactly what they saw before: the bare chip, or nothing.
  if (!canEdit) {
    return path ? <span className="tag">{path}</span> : null;
  }

  if (!path) {
    return (
      <Badge variant="default" title="This page names no cluster, so it belongs to no hub.">
        unclustered
      </Badge>
    );
  }

  if (isHub) {
    const count = clusterStatus.member_count ?? 0;
    const parts = ['HUB', `declares ${path}`];
    if (clusterStatus.parent) parts.push(`parent ${clusterStatus.parent}`);
    parts.push(`${count} page${count === 1 ? '' : 's'}`);
    return (
      <span
        className="cluster-declaration"
        data-testid="cluster-declaration"
        title="This page is the hub that declares this cluster."
      >
        {parts.join(' · ')}
      </span>
    );
  }

  if (clusterStatus.hub_declared) {
    return (
      <Link className="tag" to={`/wiki/${clusterStatus.hub_slug}`} title={`Declared by ${clusterStatus.hub_slug}`}>
        {path}
      </Link>
    );
  }

  return (
    <>
      <span className="tag">{path}</span>
      <Badge variant="default" title={`No hub page declares "${path}" yet.`}>
        cluster not yet defined
      </Badge>
    </>
  );
}
