import { useState } from 'react';

const keyFor = (id) => `wikantik.section.${id}`;

export default function CollapsibleSection({ id, icon, title, count, defaultOpen = true, children }) {
  const [open, setOpen] = useState(() => {
    const saved = localStorage.getItem(keyFor(id));
    return saved === null ? defaultOpen : saved === '1';
  });

  const toggle = () => {
    setOpen((prev) => {
      const next = !prev;
      try { localStorage.setItem(keyFor(id), next ? '1' : '0'); } catch { /* best-effort */ }
      return next;
    });
  };

  return (
    <div className="sidebar-section personal-section">
      <button
        type="button"
        className="personal-section-header"
        aria-expanded={open}
        onClick={toggle}
      >
        {icon && <span className="personal-section-icon" aria-hidden="true">{icon}</span>}
        <span className="personal-section-title">{title}</span>
        {typeof count === 'number' && <span className="personal-section-count">{count}</span>}
        <span className="personal-section-chevron" aria-hidden="true">{open ? '▾' : '▸'}</span>
      </button>
      {open && <div className="personal-section-body">{children}</div>}
    </div>
  );
}
