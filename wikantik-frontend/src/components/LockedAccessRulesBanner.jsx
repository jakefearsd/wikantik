import Icon from './ui/Icon';

// Administrator-only banner (#104): the server sends `accessRules` on a page payload only to callers holding
// AllPermission, and only when the page's access rules cannot be read — such a page is locked to administrators.
// Pure function of props; renders nothing when the key is absent.
export default function LockedAccessRulesBanner({ accessRules }) {
  if (!accessRules || !accessRules.locked) return null;
  const reasons = Array.isArray(accessRules.reasons) ? accessRules.reasons : [];
  return (
    <div className="locked-rules-banner" role="alert" data-testid="locked-access-rules-banner">
      <Icon name="warning" size={16} className="locked-rules-banner-icon" />
      <div>
        <strong>Access rules on this page can&apos;t be read</strong>, so only administrators can use it until they are
        fixed. Write each rule as <code>[{'{'}ALLOW permission name,…{'}'}]</code> on one line of the body; to show the
        syntax as text, put it in <code>code</code>.
        <ul className="locked-rules-banner-reasons">
          {reasons.map((r, i) => (
            <li key={i}>
              Line {r.line}: <code>{r.text}</code> <span className="locked-rules-banner-shape">({r.shape})</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
