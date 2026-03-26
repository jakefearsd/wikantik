import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { titleToSlug, isValidSlug } from '../utils/slugUtils';

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

  // Escape key closes the modal
  useEffect(() => {
    if (!isOpen) return;
    const handler = (e) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [isOpen, onClose]);

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
    const frontmatterLines = [
      '---',
      `type: ${articleType}`,
      'status: active',
      `date: ${today}`,
    ];
    if (cluster.trim()) {
      frontmatterLines.push(`cluster: ${cluster.trim()}`);
    }
    frontmatterLines.push('---');
    const initialContent = frontmatterLines.join('\n') + '\n\n# ' + title + '\n\nWrite your article here.';
    navigate('/edit/' + slug, { state: { initialContent } });
    onClose();
  };

  const typeOptions = ['article', 'hub', 'reference'];

  return (
    <div className="search-overlay" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="search-dialog" style={{ maxWidth: '480px' }}>
        <div style={{ padding: 'var(--space-xl)' }}>
          <h2 style={{
            fontFamily: 'var(--font-display)',
            fontSize: '1.5rem',
            marginBottom: 'var(--space-lg)',
            textAlign: 'center',
          }}>
            New Article
          </h2>

          {/* Title */}
          <div style={{ marginBottom: 'var(--space-md)' }}>
            <label style={{
              display: 'block',
              fontSize: '0.8rem',
              fontWeight: 500,
              color: 'var(--text-muted)',
              marginBottom: 'var(--space-xs)',
            }}>Title</label>
            <input
              type="text"
              value={title}
              onChange={handleTitleChange}
              autoFocus
              style={{
                width: '100%',
                padding: 'var(--space-sm) var(--space-md)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                fontSize: '1rem',
                background: 'var(--bg)',
                boxSizing: 'border-box',
              }}
            />
          </div>

          {/* Slug / Page Name */}
          <div style={{ marginBottom: 'var(--space-md)' }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--space-sm)', marginBottom: 'var(--space-xs)' }}>
              <label style={{
                fontSize: '0.8rem',
                fontWeight: 500,
                color: 'var(--text-muted)',
              }}>Page Name</label>
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
              type="text"
              value={slug}
              onChange={handleSlugChange}
              style={{
                width: '100%',
                padding: 'var(--space-sm) var(--space-md)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                fontSize: '0.9rem',
                fontFamily: 'var(--font-mono)',
                background: 'var(--bg-elevated)',
                boxSizing: 'border-box',
              }}
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
            <label style={{
              display: 'block',
              fontSize: '0.8rem',
              fontWeight: 500,
              color: 'var(--text-muted)',
              marginBottom: 'var(--space-xs)',
            }}>Cluster <span style={{ fontWeight: 400 }}>(optional)</span></label>
            <input
              type="text"
              list="cluster-options"
              value={cluster}
              onChange={e => setCluster(e.target.value)}
              style={{
                width: '100%',
                padding: 'var(--space-sm) var(--space-md)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                fontSize: '1rem',
                background: 'var(--bg)',
                boxSizing: 'border-box',
              }}
            />
            <datalist id="cluster-options">
              {existingClusters && existingClusters.map(c => (
                <option key={c} value={c} />
              ))}
            </datalist>
          </div>

          {/* Article Type */}
          <div style={{ marginBottom: 'var(--space-lg)' }}>
            <label style={{
              display: 'block',
              fontSize: '0.8rem',
              fontWeight: 500,
              color: 'var(--text-muted)',
              marginBottom: 'var(--space-xs)',
            }}>Type</label>
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
          <div style={{ display: 'flex', gap: 'var(--space-sm)', justifyContent: 'flex-end' }}>
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
        </div>
      </div>
    </div>
  );
}
