import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';

const KEY = 'wikantik.editor.mode';
// Where the stored mode came from: 'server' (a signed-in account's value, mirrored or just saved to it) or 'local'
// (an explicit choice made in this browser while signed out). Only a local choice is ever migrated into a profile,
// so one account's mirrored value can't leak into the next account that signs in on a shared browser. A legacy
// value with no provenance counts as local: migrating it is what the migration is for.
const SRC_KEY = 'wikantik.editor.mode.src';
const DEFAULT_MODE = 'live';

function isMode(v) {
  return v === 'live' || v === 'source';
}

/** This browser's remembered editor mode, or null when there is none (or storage is unavailable). */
export function readStoredEditorMode() {
  try {
    const stored = localStorage.getItem(KEY);
    if (isMode(stored)) return stored;
  } catch (err) {
    console.warn('[editor-mode] could not read the saved editor mode', err?.message || err);
  }
  return null;
}

/** Whether this browser's remembered mode is an account's value ('server') rather than a local choice. */
function storedIsServerValue() {
  try {
    return localStorage.getItem(SRC_KEY) === 'server';
  } catch (err) {
    console.warn('[editor-mode] could not read where the saved editor mode came from', err?.message || err);
    return false;
  }
}

/** Remembers `mode` in this browser, with its provenance: 'server' (an account's value) or 'local'. */
export function rememberEditorMode(mode, source) {
  try {
    localStorage.setItem(KEY, mode);
    localStorage.setItem(SRC_KEY, source);
  } catch (err) {
    console.warn('[editor-mode] could not save the editor mode', err?.message || err);
  }
}

/**
 * Source vs live-preview editing. Resolution: the signed-in user's server preference, else this
 * browser's remembered choice, else live. {@code persist=false} (legacy-syntax pages) never writes
 * anywhere and never migrates.
 */
export function useEditorMode({ authenticated = false, persist = true } = {}) {
  const [mode, setMode] = useState(() => readStoredEditorMode() ?? DEFAULT_MODE);
  const modeRef = useRef(mode);
  modeRef.current = mode;
  // Persist from an effect, not the state updater: StrictMode double-invokes updaters. Only after a toggle,
  // so merely mounting never writes the default.
  const dirty = useRef(false);
  const toggledSource = useRef('local'); // the provenance of the latest toggle, recorded with it
  const fetched = useRef(false);
  const persistRef = useRef(persist);
  persistRef.current = persist; // the fetch resolves later; the page may have turned out to be legacy meanwhile
  const mounted = useRef(true);
  // A mount flag (not a per-effect flag): StrictMode runs effect cleanup between its two passes.
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    if (!dirty.current || !persist) return;
    rememberEditorMode(mode, toggledSource.current);
  }, [mode, persist]);

  useEffect(() => {
    if (!authenticated || !persist || fetched.current) return undefined;
    fetched.current = true;
    Promise.resolve().then(() => api.getProfile()).then((profile) => {
      if (!mounted.current || dirty.current || !persistRef.current) return; // a toggle made meanwhile is newer than the server's answer
      const server = profile?.editorMode;
      if (isMode(server)) {
        setMode(server);
        rememberEditorMode(server, 'server');
        return;
      }
      const local = readStoredEditorMode();
      if (local && !storedIsServerValue()) {
        // One-time migration of this browser's explicit choice to the account.
        Promise.resolve(api.updateProfile({ editorMode: local })).catch((err) => {
          console.warn('[editor-mode] could not migrate the editor mode to the profile', err?.message || err);
        });
      }
    }).catch((err) => {
      console.warn('[editor-mode] could not load the editor mode preference', err?.message || err);
    });
    return undefined;
  }, [authenticated, persist]);

  const toggle = useCallback(() => {
    dirty.current = true;
    const next = modeRef.current === 'live' ? 'source' : 'live';
    // A signed-in toggle is saved to the account, so this browser holds that account's value, not a local choice.
    toggledSource.current = authenticated ? 'server' : 'local';
    setMode(next);
    if (authenticated && persist) {
      Promise.resolve(api.updateProfile({ editorMode: next })).catch((err) => {
        console.warn('[editor-mode] could not save the editor mode to the profile', err?.message || err);
      });
    }
  }, [authenticated, persist]);

  return [mode, toggle];
}
