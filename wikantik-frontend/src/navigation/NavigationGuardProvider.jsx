import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from '../components/ui/Modal';
import { interceptableHref, isSpaPath, markLeaving } from './navigationGuard';

const GuardContext = createContext(null);

export function NavigationGuardProvider({ children }) {
  const navigate = useNavigate();
  const active = useRef(0);
  const [pending, setPending] = useState(null); // { to, options } for router routes, { href } for full loads

  const register = useCallback(() => {
    active.current += 1;
    return () => { active.current -= 1; };
  }, []);

  const request = useCallback((to, options) => {
    if (active.current > 0) { setPending({ to, options }); return; }
    navigate(to, options);
  }, [navigate]);

  useEffect(() => {
    // Capture phase on document runs before React Router's <Link> handler, so cancelling here stops it.
    const onClick = (e) => {
      if (active.current === 0) return;
      const to = interceptableHref(e);
      if (!to) return;
      e.preventDefault();
      e.stopPropagation();
      // Server-served links (attachments, /sparql, …) have no route: Leave must load them as a full page.
      setPending(isSpaPath(to) ? { to } : { href: e.target.closest('a[href]').href });
    };
    document.addEventListener('click', onClick, true);
    return () => document.removeEventListener('click', onClick, true);
  }, []);

  const value = useMemo(() => ({ register, request }), [register, request]);
  const stay = () => setPending(null);
  const leave = () => {
    const p = pending;
    setPending(null);
    if (p.href) { markLeaving(); window.location.assign(p.href); }
    else navigate(p.to, p.options);
  };

  return (
    <GuardContext.Provider value={value}>
      {children}
      {pending && (
        <Modal isOpen onClose={stay} labelledBy="guard-dialog-title" testId="guard-dialog">
          <h2 id="guard-dialog-title">Unsaved changes</h2>
          <p>You have unsaved changes. Your draft is kept in this browser but isn&apos;t saved to the wiki.</p>
          <div className="modal-actions">
            <button type="button" className="btn" data-testid="guard-stay" onClick={stay} autoFocus>Stay</button>
            <button type="button" className="btn btn-danger" data-testid="guard-leave" onClick={leave}>
              Leave without saving
            </button>
          </div>
        </Modal>
      )}
    </GuardContext.Provider>
  );
}

/** Holds in-app navigation behind the guard dialog while {@code active} is true. */
export function useNavigationGuard(active) {
  const ctx = useContext(GuardContext);
  useEffect(() => {
    if (!active || !ctx) return undefined;
    return ctx.register();
  }, [active, ctx]);
}

/** navigate() that asks first when a guard is active. Outside a provider it is plain navigate(). */
export function useGuardedNavigate() {
  const ctx = useContext(GuardContext);
  const navigate = useNavigate();
  return ctx ? ctx.request : navigate;
}
