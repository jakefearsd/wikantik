import { useCallback, useEffect, useRef, useState } from 'react';
import { getCaretCoordinates } from '../utils/caretCoords';

/**
 * Pure-logic hook for tracking an in-flight `@<token>` mention under a
 * textarea's caret. Debounces candidate fetches and exposes UI-agnostic
 * state so any popover/list component can render against it.
 *
 * Contract:
 *   const picker = useMentionPicker({ textareaRef, fetchCandidates });
 *
 *   textareaRef:      React ref to the host textarea element.
 *   fetchCandidates:  (query: string) => Promise<[{loginName, fullName}]>
 *
 * Returns:
 *   open:           boolean — whether to render a popover
 *   candidates:     [{loginName, fullName}] — current candidates
 *   selectedIndex:  number — highlighted candidate in the list
 *   query:          string — substring being matched (the chars after the last '@')
 *   anchorPos:      { top, left } | null — caret position for popover anchoring
 *   onKeyDown(ev):  boolean — handles keyboard nav; returns true if consumed
 *   onChange(ev):   void — call from textarea's onChange to update picker state
 *   accept(login):  { replacement, selectionStart, selectionEnd } — apply selection
 *   close():        void — manually close (e.g., on blur)
 */

const TOKEN_RE = /^[A-Za-z0-9._-]*$/;

export function useMentionPicker({ textareaRef, fetchCandidates }) {
  const [state, setState] = useState({
    open: false,
    candidates: [],
    selectedIndex: 0,
    query: '',
    tokenStart: -1,
    tokenEnd: -1,
    anchorPos: null,
  });
  const timerRef = useRef(null);
  const lastQueryRef = useRef(null);

  const close = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    lastQueryRef.current = null;
    setState((s) => ({
      ...s,
      open: false,
      candidates: [],
      query: '',
      tokenStart: -1,
      tokenEnd: -1,
      selectedIndex: 0,
      anchorPos: null,
    }));
  }, []);

  const onChange = useCallback(() => {
    const ta = textareaRef.current;
    if (!ta) {
      close();
      return;
    }
    const caret = ta.selectionStart;
    const value = ta.value;
    // Walk back from caret looking for '@'; stop on whitespace.
    let i = caret - 1;
    let tokenStart = -1;
    while (i >= 0) {
      const ch = value.charAt(i);
      if (ch === '@') {
        tokenStart = i;
        break;
      }
      if (/\s/.test(ch)) break;
      i--;
    }
    if (tokenStart < 0) {
      close();
      return;
    }
    const between = value.substring(tokenStart + 1, caret);
    if (!TOKEN_RE.test(between)) {
      close();
      return;
    }
    const caretCoords = getCaretCoordinates(ta);
    setState((s) => ({
      ...s,
      open: true,
      query: between,
      tokenStart,
      tokenEnd: caret,
      selectedIndex: 0,
      anchorPos: caretCoords,
    }));
    // Debounced fetch.
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(async () => {
      lastQueryRef.current = between;
      try {
        const result = await fetchCandidates(between);
        // Drop stale results if the user typed further.
        if (lastQueryRef.current !== between) return;
        setState((s) => ({ ...s, candidates: Array.isArray(result) ? result : [], selectedIndex: 0 }));
      } catch {
        setState((s) => ({ ...s, candidates: [] }));
      }
    }, 150);
  }, [textareaRef, fetchCandidates, close]);

  const onKeyDown = useCallback((event) => {
    if (!state.open) return false;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setState((s) => ({
        ...s,
        selectedIndex: Math.min(s.selectedIndex + 1, Math.max(s.candidates.length - 1, 0)),
      }));
      return true;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setState((s) => ({ ...s, selectedIndex: Math.max(s.selectedIndex - 1, 0) }));
      return true;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      return true;
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault();
      return true; // caller handles via accept(selectedLogin)
    }
    return false;
  }, [state.open, close]);

  const accept = useCallback((login) => {
    const ta = textareaRef.current;
    if (!ta) {
      return { replacement: '', selectionStart: 0, selectionEnd: 0 };
    }
    const { tokenStart, tokenEnd } = state;
    const value = ta.value;
    const before = tokenStart >= 0 ? value.substring(0, tokenStart) : value;
    const after = tokenEnd >= 0 ? value.substring(tokenEnd) : '';
    const inserted = `@${login} `;
    const replacement = before + inserted + after;
    const caretAfter = (before + inserted).length;
    close();
    return { replacement, selectionStart: caretAfter, selectionEnd: caretAfter };
  }, [textareaRef, state, close]);

  // Cleanup on unmount.
  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current);
  }, []);

  return {
    open: state.open,
    candidates: state.candidates,
    selectedIndex: state.selectedIndex,
    query: state.query,
    anchorPos: state.anchorPos,
    onChange,
    onKeyDown,
    accept,
    close,
  };
}

export default useMentionPicker;
