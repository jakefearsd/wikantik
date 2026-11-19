import { useEffect, useLayoutEffect, useRef, useState } from 'react';

/** For a control's `onMouseDown`: a press on it must not take the focus (and the caret) out of the editor. */
export const keepEditorFocus = (e) => e.preventDefault();

/**
 * A toolbar button that opens a menu. Opening focuses the checked item (else the first enabled one); Up/Down/Home/End
 * move, Escape closes and returns to the trigger, choosing closes and hands the item's id to `onChoose`.
 *
 * `items`: [{ id, label, hint?, disabled?, checked?, separatorBefore? }]; `checked` (a boolean) makes the item a
 * `checkable` role (menuitemradio / menuitemcheckbox), and leaves a plain menuitem when undefined. `caret={false}`
 * leaves the ▾ off a trigger whose face already says menu (⋮).
 */
export default function ToolbarMenu({ name, title, ariaLabel, face, items, checkable = 'menuitemcheckbox', onChoose, itemProps, className = '', caret = true }) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef(null);
  const triggerRef = useRef(null);
  const menuRef = useRef(null);

  const enabledItems = () => [...(menuRef.current?.querySelectorAll('[data-label]:not(:disabled)') ?? [])];

  useLayoutEffect(() => {
    const menu = menuRef.current;
    if (!open || !menu) return;
    // Opens under the trigger's left edge unless that would run off the right of the window.
    menu.classList.toggle('editor-format-menu-end', menu.getBoundingClientRect().right > window.innerWidth);
    const enabled = [...menu.querySelectorAll('[data-label]:not(:disabled)')];
    (enabled.find((el) => el.getAttribute('aria-checked') === 'true') ?? enabled[0])?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const onPress = (e) => { if (!wrapRef.current?.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', onPress);
    return () => document.removeEventListener('mousedown', onPress);
  }, [open]);

  const onMenuKeyDown = (e) => {
    if (e.key === 'Tab') { setOpen(false); return; }
    e.stopPropagation(); // the toolbar's own arrow keys must not see a menu's
    if (e.key === 'Escape') {
      e.preventDefault();
      setOpen(false);
      triggerRef.current?.focus();
      return;
    }
    const enabled = enabledItems();
    const at = enabled.indexOf(document.activeElement);
    const to = { ArrowDown: at + 1, ArrowUp: at - 1, Home: 0, End: enabled.length - 1 }[e.key];
    if (to === undefined || enabled.length === 0) return;
    e.preventDefault();
    enabled[(to + enabled.length) % enabled.length].focus();
  };

  return (
    <span ref={wrapRef} className="editor-format-menu-wrap">
      <button
        ref={triggerRef}
        type="button"
        className={`editor-format-btn editor-format-menu-trigger ${className}`}
        title={title}
        aria-label={ariaLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        onMouseDown={keepEditorFocus}
        onClick={() => setOpen((was) => !was)}
        onKeyDown={(e) => { if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); } }}
        {...itemProps}
      >
        {face}{caret && <span className="editor-format-menu-caret" aria-hidden="true">▾</span>}
      </button>
      {open && (
        <div ref={menuRef} className="editor-format-menu" role="menu" aria-label={name} onKeyDown={onMenuKeyDown}>
          {items.map(({ id, label, hint, disabled, checked, separatorBefore }) => (
            <span key={id} className="editor-format-menu-row" role="none">
              {separatorBefore && <span className="editor-format-menu-separator" role="separator" />}
              <button
                type="button"
                className="editor-format-menu-item"
                role={checked === undefined ? 'menuitem' : checkable}
                aria-checked={checked}
                aria-label={label}
                data-label={label}
                tabIndex={-1}
                disabled={disabled}
                onMouseDown={keepEditorFocus}
                onClick={() => { setOpen(false); onChoose(id); }}
              >
                <span>{label}</span>
                {hint && <span className="editor-format-menu-hint" aria-hidden="true">{hint}</span>}
              </button>
            </span>
          ))}
        </div>
      )}
    </span>
  );
}
