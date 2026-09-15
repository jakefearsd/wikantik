import { useEffect, useSyncExternalStore } from 'react';

const STORAGE_KEY = 'wikantik-theme';

// Module-level shared state so every useDarkMode() consumer stays in sync.
// Previously each instance held its own useState, so toggling the theme in the
// sidebar never updated the editor's instance — CodeMirror kept its old theme
// until a full refresh re-read localStorage. `current` is a genuine external
// store (mutated outside React, by toggle()), so it's read via
// useSyncExternalStore rather than an effect that calls setState.
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

function subscribe(onStoreChange) {
  listeners.add(onStoreChange);
  return () => { listeners.delete(onStoreChange); };
}

export function useDarkMode() {
  const dark = useSyncExternalStore(subscribe, getCurrent);

  // Reflect the current theme in the DOM/localStorage — an imperative sync to
  // an external system, not a setState call, so it's fine inside an effect.
  useEffect(() => {
    applyTheme(dark);
  }, [dark]);

  const toggle = () => {
    current = !getCurrent();
    applyTheme(current);
    listeners.forEach(notify => notify()); // update every mounted consumer
  };

  return [dark, toggle];
}
