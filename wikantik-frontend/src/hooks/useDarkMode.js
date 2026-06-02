import { useState, useEffect } from 'react';

const STORAGE_KEY = 'wikantik-theme';

// Module-level shared state so every useDarkMode() consumer stays in sync.
// Previously each instance held its own useState, so toggling the theme in the
// sidebar never updated the editor's instance — CodeMirror kept its old theme
// until a full refresh re-read localStorage.
const listeners = new Set();
let current = null;

function readInitial() {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored) return stored === 'dark';
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function getCurrent() {
  if (current === null) current = readInitial();
  return current;
}

function applyTheme(dark) {
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light');
}

export function useDarkMode() {
  const [dark, setDark] = useState(getCurrent);

  useEffect(() => {
    // Reflect the current theme in the DOM on mount, and subscribe so a toggle
    // from any other consumer updates this instance too.
    applyTheme(getCurrent());
    if (dark !== current) setDark(current);
    listeners.add(setDark);
    return () => { listeners.delete(setDark); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggle = () => {
    const next = !getCurrent();
    current = next;
    applyTheme(next);
    listeners.forEach(notify => notify(next)); // update every mounted consumer
  };

  return [dark, toggle];
}
