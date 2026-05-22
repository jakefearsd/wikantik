// PageHeader.jsx
// Uniform admin page header: serif title (editorial chrome) + description +
// right-aligned actions slot. Adopted by every admin page so headers stop drifting.
export default function PageHeader({ title, description, actions }) {
  return (
    <header className="page-header">
      <div className="page-header-text">
        <h1 className="page-header-title">{title}</h1>
        {description && <p className="page-header-desc">{description}</p>}
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
    </header>
  );
}
