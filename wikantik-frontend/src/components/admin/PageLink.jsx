import { pageHref, normalizePageName } from '../../utils/pageUrl';

/**
 * Anchor to a wiki page view URL. Opens in a new tab so admin/knowledge
 * panels don't lose their state (filters, selection, scroll) when a page
 * is inspected. Renders a plain span when no name is supplied.
 */
export default function PageLink({ name, children, className }) {
  const href = pageHref(name);
  const display = children ?? normalizePageName(name);
  if (!href) return <span className={className}>{display}</span>;
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className={className}
      title={`Open ${normalizePageName(name)} in new tab`}
    >
      {display}
    </a>
  );
}
