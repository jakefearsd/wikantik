import { useCallback, useEffect, useState } from 'react';
import { registerCommands } from '../commands/registry';
import { buildBlockPickerCommands } from '../utils/editorCommands';
import { liveModeField } from '../utils/livePreview';
import { pickerBlockAt, focusBlockPicker } from '../utils/livePreview/headers';

/**
 * The keyboard's way to a block's header picker in Rich mode (a code block's language, a diagram's kind, a callout's
 * type). The caret cannot rest on a header line and Tab indents, so nothing else can focus the picker.
 *
 * - `onViewChange()` goes to CodeEditor's `onViewChange`: it follows whether the caret is in a fenced block or a callout.
 * - While it is (in Rich, on a document that can be edited), the palette command "Change code language / callout
 *   type" is registered; it moves the focus to that block's picker, where Enter or Space opens the list and a choice
 *   writes the one word (headers.js). Escape there, or a choice, returns the focus to the text.
 *
 * The command itself writes nothing, and does nothing when it no longer applies (a run held from before).
 */
export function useBlockPicker({ editorRef, readOnly = false }) {
  const [inBlock, setInBlock] = useState(false);

  const viewOf = useCallback(() => {
    const view = editorRef.current?.getView?.();
    return view && typeof view.state?.field === 'function' ? view : null; // a stub view (tests) has no fields
  }, [editorRef]);

  const applies = useCallback((view) => !!view && view.state.field(liveModeField, false) === true
    && !view.state.readOnly && pickerBlockAt(view.state) >= 0, []);

  const onViewChange = useCallback(() => setInBlock(applies(viewOf())), [applies, viewOf]);

  const focusPicker = useCallback(() => {
    const view = viewOf();
    if (applies(view)) focusBlockPicker(view);
  }, [applies, viewOf]);

  const shown = inBlock && !readOnly;
  useEffect(() => (shown ? registerCommands(buildBlockPickerCommands({ focusPicker })) : undefined), [shown, focusPicker]);

  return { onViewChange };
}
