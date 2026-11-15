import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import EmptyState from '../ui/EmptyState';
import Spinner from '../ui/Spinner';
import { getMe } from '../../api/work';
import { useAuth } from '../../hooks/useAuth';

/** /work: the viewer's projects and the items waiting on them. */
export default function WorkHome() {
  const { user } = useAuth();
  const isAdmin = !!user?.roles?.includes('Admin');
  const [state, setState] = useState({ me: null, error: null });

  useEffect(() => {
    getMe()
      .then((me) => setState({ me, error: null }))
      .catch((error) => setState({ me: null, error }));
  }, []);

  if (state.error) return <EmptyState message={state.error.message || 'Could not load your projects.'} />;
  if (!state.me) return <Spinner label="Loading…" />;
  const { projects = [], waitingOn = [] } = state.me;
  const keyOf = new Map(projects.map((p) => [p.id, p.key]));

  return (
    <div className="work-home">
      <h1 className="work-heading">Work</h1>
      {isAdmin && <p><Link to="/work/products">Products</Link></p>}
      {waitingOn.length > 0 && (
        <section className="work-waiting" data-testid="waiting-lane">
          <strong>Waiting on you ({waitingOn.length})</strong>
          <ul className="work-waiting-list">
            {waitingOn.map((i) => (
              <li key={i.id}><Link to={`/work/item/${i.key}`}>{i.key}</Link> {i.title}
                {keyOf.has(i.projectId) && <span className="work-muted"> · {keyOf.get(i.projectId)}</span>}</li>
            ))}
          </ul>
        </section>
      )}
      <h2 className="work-subheading">Projects</h2>
      {projects.length === 0
        ? <EmptyState message="You are not a member of any project yet." />
        : (
          <ul className="work-project-list">
            {projects.map((p) => (
              <li key={p.id}><Link to={`/work/${p.key}`}>{p.key} {p.name}</Link>
                <span className="work-muted"> · {p.role}</span></li>
            ))}
          </ul>
        )}
    </div>
  );
}
