import { useAuth } from '../hooks/useAuth';
import { track, getReaderPageContext } from '../analytics/umami';

/**
 * Renders a "Continue with <provider>" button that starts the server-side SSO
 * flow, shown only when SSO is enabled (per the `sso` descriptor on
 * /api/auth/user). Under auto-provisioning this is also the account-creation
 * path, so it carries a short hint for first-time visitors.
 *
 * /sso/login is a servlet, not an SPA route, so this is a deliberate full-page
 * navigation (plain anchor) rather than a react-router link.
 *
 * The link carries `?return=<page>` so the server lands the user back where they
 * started signing in, not on the front page. `returnTo` is a full browser path
 * (router basename included); omitted, it is the current page; null sends none.
 * The server re-validates it and falls back to the front page.
 */
export default function SsoLoginButton({ returnTo }) {
  const { sso } = useAuth();
  if (!sso?.enabled) return null;

  const label = sso.providerLabel || 'single sign-on';
  const target = returnTo === undefined
    ? window.location.pathname + window.location.search + window.location.hash
    : returnTo;
  const href = target ? `${sso.loginUrl}?return=${encodeURIComponent(target)}` : sso.loginUrl;

  return (
    <div data-testid="sso-login">
      <a
        href={href}
        onClick={() => track('sign-in-click', { canonical_id: getReaderPageContext().canonicalId })}
        className="btn btn-primary"
        data-testid="sso-login-button"
        style={{
          width: '100%',
          justifyContent: 'center',
          padding: 'var(--space-sm) var(--space-lg)',
          textDecoration: 'none',
          display: 'inline-flex',
        }}
      >
        Continue with {label}
      </a>
      <p style={{
        fontSize: '0.75rem',
        color: 'var(--text-muted)',
        textAlign: 'center',
        margin: 'var(--space-xs) 0 var(--space-md)',
      }}>
        New here? Signing in creates your account.
      </p>
      <div data-testid="sso-divider" style={{
        display: 'flex',
        alignItems: 'center',
        gap: 'var(--space-sm)',
        color: 'var(--text-muted)',
        fontSize: '0.75rem',
        marginBottom: 'var(--space-md)',
      }}>
        <span style={{ flex: 1, height: '1px', background: 'var(--border)' }} />
        or
        <span style={{ flex: 1, height: '1px', background: 'var(--border)' }} />
      </div>
    </div>
  );
}
