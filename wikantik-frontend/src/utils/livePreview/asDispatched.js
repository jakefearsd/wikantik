import { Annotation } from '@codemirror/state';

/**
 * Marks a transaction whose document changes are exact and must be applied as they are: a programmatic write — the
 * link card's, a table command's, a server-decided block id, a background rewrite — as opposed to what the user types,
 * pastes, drops or deletes at the selection. `asDispatched.of(true)`.
 *
 * A transaction filter that rewrites local input (tableGrid.js's cell rules, which tell input by its user event and
 * by its change touching a selection range) returns such a transaction untouched, whatever its user event and
 * wherever the selection is. Filters that only add a selection (the caret rules) still run: the changes are what is
 * protected. CodeEditor's `applyChanges` sets it on every write; anything else that dispatches an exact change with an
 * `input` or `delete` user event sets it itself. The editor's own input handling never does, so typing cannot carry it.
 */
export const asDispatched = Annotation.define();
