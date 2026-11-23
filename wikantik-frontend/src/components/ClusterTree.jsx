import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import CollapsibleSection from './CollapsibleSection';

const byTitle = (a, b) => (a.title || a.slug).localeCompare(b.title || b.slug);

/** The pages of one cluster, fetched when its section is first opened. */
function ClusterMembers({ cluster, hub, activePage, onNavigate }) {
  // null = loading, false = failed, array = loaded.
  const [members, setMembers] = useState(null);
  useEffect(() => {
    let live = true;
    api.listClusterPages(cluster)
      .then((d) => { if (live) setMembers(d?.pages || []); })
      .catch((e) => {
        console.warn(`Sidebar: failed to load cluster ${cluster}`, e);
        if (live) setMembers(false);
      });
    return () => { live = false; };
  }, [cluster]);

  const link = (p, label) => (
    <Link
      key={p.slug}
      to={`/wiki/${p.slug}`}
      className={`sidebar-link ${activePage === p.slug ? 'active' : ''}`}
      onClick={onNavigate}
      {...(activePage === p.slug ? { 'aria-current': 'page' } : {})}
    >
      {label}
    </Link>
  );

  // The server answers for a cluster with its sub-clusters' pages too; each sub-cluster has its own section.
  const own = Array.isArray(members)
    ? members.filter((p) => p.slug !== hub?.slug && !(p.cluster || '').startsWith(`${cluster}/`)).sort(byTitle)
    : [];
  return (
    <>
      {hub && link(hub, <strong>{hub.title || hub.slug}</strong>)}
      {members === null && <div className="personal-empty">Loading…</div>}
      {members === false && <div className="personal-empty">Couldn't load this cluster.</div>}
      {own.map((p) => link(p, p.title || p.slug))}
    </>
  );
}

/**
 * Every cluster the wiki declares, each a collapsed section that loads its pages when opened.
 * Mounted only while "Browse Clusters" is open, so a reader who never opens it costs no request.
 */
export default function ClusterTree({ activePage, onNavigate = () => {} }) {
  const [clusters, setClusters] = useState(null);
  const [activeCluster, setActiveCluster] = useState(null);

  useEffect(() => {
    let live = true;
    api.listClusters()
      .then((d) => { if (live) setClusters(d?.clusters || []); })
      .catch((e) => {
        console.warn('Sidebar: failed to load clusters', e);
        if (live) setClusters(false);
      });
    return () => { live = false; };
  }, []);

  // Which cluster holds the page being read, so its section starts open.
  useEffect(() => {
    if (!activePage) return undefined;
    let live = true;
    api.listPages({ names: [activePage], limit: 1 })
      .then((d) => { if (live) setActiveCluster(d?.pages?.[0]?.cluster || null); })
      .catch((e) => console.warn('Sidebar: failed to find the cluster of the current page', e));
    return () => { live = false; };
  }, [activePage]);

  if (clusters === null) return <div className="personal-empty">Loading…</div>;
  if (clusters === false) return <div className="personal-empty">Couldn't load the clusters.</div>;
  if (clusters.length === 0) return <div className="personal-empty">No clusters yet.</div>;

  return clusters
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((c) => {
      const active = c.name === activeCluster;
      return (
        // Remount when the cluster turns out to hold the current page: defaultOpen is read once.
        <CollapsibleSection
          key={`${c.name}:${active}`}
          id={`cluster-${c.name}`}
          title={c.name}
          count={c.article_count}
          defaultOpen={active}
        >
          <ClusterMembers cluster={c.name} hub={c.hub_page} activePage={activePage} onNavigate={onNavigate} />
        </CollapsibleSection>
      );
    });
}
