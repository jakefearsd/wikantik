import { pageEditHref, normalizePageName } from '../../utils/pageUrl';

/**
 * Anchor to a wiki page edit URL. Opens in a new tab so admin/knowledge
 * panels don't lose state when the editor is opened for a page.
 */
export default function PageEditLink({ name, children, className }) {
  const href = pageEditHref(name);
  const display = children ?? normalizePageName(name);
  if (!href) return <span className={className}>{display}</span>;
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className={className}
      title={`Edit ${normalizePageName(name)} in new tab`}
    >
      {display}
    </a>
  );
}
