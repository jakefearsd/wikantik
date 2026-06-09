import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { titleToSlug, isValidSlug } from '../utils/slugUtils';
import Modal from './ui/Modal';

export default function NewArticleModal({ isOpen, onClose, existingPageNames, existingClusters }) {
  const navigate = useNavigate();
  const [title, setTitle] = useState('');
  const [slug, setSlug] = useState('');
  const [slugIsManual, setSlugIsManual] = useState(false);
  const [cluster, setCluster] = useState('');
  const [articleType, setArticleType] = useState('article');

  // Reset state when modal opens
  useEffect(() => {
    if (isOpen) {
      setTitle('');
      setSlug('');
      setSlugIsManual(false);
      setCluster('');
      setArticleType('article');
    }
  }, [isOpen]);

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

  const isDuplicate = existingPageNames instanceof Set
    ? existingPageNames.has(slug)
    : false;

  const isValid = isValidSlug(slug) && title.trim().length > 0;

  const handleSubmit = () => {
    if (!isValid) return;
    const today = new Date().toISOString().slice(0, 10);
    // Hand the editor a structured metadata object + a frontmatter-free body; the structured
    // FrontmatterEditor takes over from here (no more hand-built raw-YAML string).
    const initialMetadata = { type: articleType, status: 'active', date: today };
    if (cluster.trim()) {
      initialMetadata.cluster = cluster.trim();
    }
    const initialContent = `# ${title}\n\nWrite your article here.`;
    navigate('/edit/' + slug, { state: { initialMetadata, initialContent } });
    onClose();
  };

  const typeOptions = ['article', 'hub', 'reference'];

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
          Cluster <span style={{ fontWeight: 400 }}>(optional)</span>
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
          {existingClusters && existingClusters.map(c => (
            <option key={c} value={c} />
          ))}
        </datalist>
      </div>

      {/* Article Type */}
      <div style={{ marginBottom: 'var(--space-lg)' }}>
        <label className="field-label">Type</label>
        <div style={{ display: 'flex', gap: 'var(--space-xs)' }}>
          {typeOptions.map(type => (
            <button
              key={type}
              type="button"
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
              {type}
            </button>
          ))}
        </div>
      </div>

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
