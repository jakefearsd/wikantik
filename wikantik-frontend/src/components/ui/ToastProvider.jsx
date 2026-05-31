import { useState, useCallback, useRef, createContext, useContext } from 'react';

export const ToastContext = createContext(null);

const AUTO_DISMISS_MS = 5000;
const MAX_TOASTS = 4;

let _nextId = 1;

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const timers = useRef({});

  const dismiss = useCallback((id) => {
    clearTimeout(timers.current[id]);
    delete timers.current[id];
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  const addToast = useCallback((type, message) => {
    setToasts(prev => {
      // Dedupe: if the most-recent toast has same type+message, skip
      const last = prev[prev.length - 1];
      if (last && last.type === type && last.message === message) {
        return prev;
      }

      const id = _nextId++;
      const next = [...prev, { id, type, message }];

      // Stack cap: drop oldest if over limit
      const capped = next.length > MAX_TOASTS ? next.slice(next.length - MAX_TOASTS) : next;

      // If oldest was dropped, clear its timer
      if (next.length > MAX_TOASTS) {
        const dropped = next[0];
        clearTimeout(timers.current[dropped.id]);
        delete timers.current[dropped.id];
      }

      // Schedule auto-dismiss for success and info (not error)
      if (type !== 'error') {
        timers.current[id] = setTimeout(() => {
          clearTimeout(timers.current[id]);
          delete timers.current[id];
          setToasts(curr => curr.filter(t => t.id !== id));
        }, AUTO_DISMISS_MS);
      }

      return capped;
    });
  }, []);

  const success = useCallback((message) => addToast('success', message), [addToast]);
  const error = useCallback((message) => addToast('error', message), [addToast]);
  const info = useCallback((message) => addToast('info', message), [addToast]);

  return (
    <ToastContext.Provider value={{ success, error, info, dismiss }}>
      {children}
      <div className="wk-toast-container" aria-live="polite">
        {toasts.map(toast => (
          <div
            key={toast.id}
            className={`wk-toast wk-toast-${toast.type}`}
            role={toast.type === 'error' ? 'alert' : 'status'}
          >
            <span className="wk-toast-message">{toast.message}</span>
            <button
              className="wk-toast-dismiss"
              aria-label="Dismiss"
              onClick={() => dismiss(toast.id)}
            >
              &times;
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
