import { useEffect, useState } from 'react';
import { useGuardedNavigate } from '../navigation/NavigationGuardProvider';
import { api } from '../api/client';
import { titleToSlug, isValidSlug } from '../utils/slugUtils';
import { buildInitialPage, fillTemplate } from '../utils/pageTemplates';
import Modal from './ui/Modal';

const FALLBACK_TYPES = ['article', 'reference', 'design', 'runbook', 'hub'].map((type) => ({
  type, label: type.charAt(0).toUpperCase() + type.slice(1), description: '',
}));
const BARE_BODY = '# {{title}}\n\n';

export default function NewArticleModal({ isOpen, onClose, initialTitle = '' }) {
  const navigate = useGuardedNavigate();
  const [title, setTitle] = useState(initialTitle);
  const [slug, setSlug] = useState(titleToSlug(initialTitle));
  const [slugIsManual, setSlugIsManual] = useState(false);
  const [cluster, setCluster] = useState('');
  const [articleType, setArticleType] = useState('article');
  const [templates, setTemplates] = useState(null); // null while loading
  const [templatesError, setTemplatesError] = useState(false);
  const [clusterNames, setClusterNames] = useState([]);
  const [duplicateSlug, setDuplicateSlug] = useState(null); // the slug the server says exists

  // Reset form fields whenever the modal transitions to open — derived during
  // render (store-previous-and-compare) rather than an effect.
  const [prevIsOpen, setPrevIsOpen] = useState(isOpen);
  if (isOpen !== prevIsOpen) {
    setPrevIsOpen(isOpen);
    if (isOpen) {
      setTitle(initialTitle);
      setSlug(titleToSlug(initialTitle));
      setSlugIsManual(false);
      setCluster('');
      setArticleType('article');
    }
  }

  // Templates and cluster suggestions, fetched on each open.
  useEffect(() => {
    if (!isOpen) return undefined;
    let cancelled = false;
    api.getPageTemplates()
      .then((res) => { if (!cancelled) { setTemplates(res.templates ?? []); setTemplatesError(false); } })
      .catch((err) => {
        console.warn('[new-page] templates unavailable', err);
        if (!cancelled) { setTemplates([]); setTemplatesError(true); }
      });
    api.listClusters()
      .then((res) => { if (!cancelled) setClusterNames((res.clusters ?? []).map((c) => c.name)); })
      .catch((err) => console.warn('[new-page] cluster list unavailable', err));
    return () => { cancelled = true; };
  }, [isOpen]);

  // Duplicate check: ask the server about exactly this name once the slug settles.
  useEffect(() => {
    if (!isOpen || !isValidSlug(slug)) return undefined;
    let cancelled = false;
    const timer = setTimeout(() => {
      api.listPages({ names: [slug], limit: 1 })
        .then((res) => { if (!cancelled) setDuplicateSlug((res.pages ?? []).length > 0 ? slug : null); })
        .catch((err) => console.warn('[new-page] duplicate check failed for ' + slug, err));
    }, 300);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [isOpen, slug]);

  if (!isOpen) return null;

  const handleTitleChange = (e) => {
    const newTitle = e.target.value;
    setTitle(newTitle);
    if (!slugIsManual) {
      setSlug(titleToSlug(newTitle));
    }
  };

  const handleSlugChange = (e) => {
    setSlug(e.target.value);
    setSlugIsManual(true);
  };

  const isDuplicate = duplicateSlug === slug;
  const today = new Date().toISOString().slice(0, 10);
  const template = templates?.find((t) => t.type === articleType) ?? null;
  const typeOptions = templatesError ? FALLBACK_TYPES : (templates ?? []);

  const isValid = isValidSlug(slug) && title.trim().length > 0
    && (articleType !== 'hub' || cluster.trim().length > 0);

  const handleSubmit = () => {
    if (!isValid) return;
    if (isDuplicate) {
      navigate('/edit/' + slug);
    } else {
      // Hand the editor a structured metadata object + a frontmatter-free body; the structured
      // FrontmatterEditor takes over from here.
      const { initialMetadata, initialContent } = buildInitialPage({ template, title, type: articleType, cluster, today });
      navigate('/edit/' + slug, { state: { initialMetadata, initialContent } });
    }
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} labelledBy="new-article-modal-title" className="search-dialog">
      <h2 id="new-article-modal-title" style={{
        fontFamily: 'var(--font-display)',
        fontSize: '1.5rem',
        marginBottom: 'var(--space-lg)',
        textAlign: 'center',
      }}>
        New Article
      </h2>

      {/* Title */}
      <div style={{ marginBottom: 'var(--space-md)' }}>
        <label className="field-label" htmlFor="new-article-title">Title</label>
        <input
          id="new-article-title"
          type="text"
          className="form-input"
          value={title}
          onChange={handleTitleChange}
          autoFocus
        />
      </div>

      {/* Slug / Page Name */}
      <div style={{ marginBottom: 'var(--space-md)' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--space-sm)', marginBottom: 'var(--space-xs)' }}>
          <label
            htmlFor="new-article-slug"
            style={{ fontSize: '0.8rem', fontWeight: 500, color: 'var(--text-muted)' }}
          >Page Name</label>
          <span style={{
            fontSize: '0.75rem',
            color: slugIsManual ? 'var(--accent)' : 'var(--text-muted)',
            fontStyle: 'italic',
          }}>
            {slugIsManual ? '✎ custom' : 'auto'}
          </span>
        </div>
        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: 'var(--space-xs)' }}>
          Permanent URL — cannot be changed after creation
        </div>
        <input
          id="new-article-slug"
          type="text"
          className="form-input"
          value={slug}
          onChange={handleSlugChange}
          style={{ fontFamily: 'var(--font-mono)', fontSize: '0.9rem', background: 'var(--bg-elevated)' }}
        />
      </div>

      {/* Duplicate warning */}
      {isDuplicate && (
        <div style={{
          background: '#fff8e1',
          border: '1px solid #f9a825',
          color: '#e65100',
          borderRadius: 'var(--radius-sm)',
          padding: 'var(--space-xs) var(--space-sm)',
          fontSize: '0.85rem',
          marginBottom: 'var(--space-md)',
        }}>
          This page already exists — will open editor for existing page
        </div>
      )}

      {/* Cluster */}
      <div style={{ marginBottom: 'var(--space-md)' }}>
        <label className="field-label" htmlFor="new-article-cluster">
          Cluster {articleType !== 'hub' && <span style={{ fontWeight: 400 }}>(optional)</span>}
        </label>
        <input
          id="new-article-cluster"
          type="text"
          list="cluster-options"
          className="form-input"
          value={cluster}
          onChange={e => setCluster(e.target.value)}
        />
        <datalist id="cluster-options">
          {clusterNames.map(c => (
            <option key={c} value={c} />
          ))}
        </datalist>
      </div>

      {/* Article Type */}
      <div style={{ marginBottom: 'var(--space-lg)' }}>
        <label className="field-label">Type</label>
        <div style={{ display: 'flex', gap: 'var(--space-xs)' }}>
          {typeOptions.map(({ type, label, description }) => (
            <button
              key={type}
              type="button"
              title={description}
              onClick={() => setArticleType(type)}
              style={{
                flex: 1,
                padding: 'var(--space-xs) var(--space-sm)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-sm)',
                cursor: 'pointer',
                fontSize: '0.85rem',
                fontWeight: 500,
                background: articleType === type ? 'var(--accent)' : 'var(--bg-elevated)',
                color: articleType === type ? 'white' : 'var(--text)',
                transition: 'background 0.15s, color 0.15s',
              }}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {templatesError && <p className="info-banner">Templates unavailable</p>}
      <pre className="template-preview" data-testid="template-preview" style={{
        maxHeight: '10rem', overflow: 'auto', fontSize: '0.8rem', fontFamily: 'var(--font-mono)',
        background: 'var(--bg-elevated)', border: '1px solid var(--border)',
        borderRadius: 'var(--radius-sm)', padding: 'var(--space-sm)', marginBottom: 'var(--space-md)',
      }}>
        {fillTemplate(template?.body ?? BARE_BODY, { title: title || 'Title', date: today })}
      </pre>

      {/* Button row */}
      <div className="modal-actions">
        <button className="btn btn-ghost" type="button" onClick={onClose}>
          Cancel
        </button>
        <button
          className="btn btn-primary"
          type="button"
          onClick={handleSubmit}
          disabled={!isValid}
        >
          {isDuplicate ? 'Open Editor' : 'Create'}
        </button>
      </div>
    </Modal>
  );
}
