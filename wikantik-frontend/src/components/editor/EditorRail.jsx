import { useEffect, useMemo } from 'react';
import BacklinksPanel from '../BacklinksPanel';
import { headingsFromMarkdown } from '../../utils/headings';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useEditorCursor } from '../../hooks/useEditorCursor';

const OUTLINE_DEBOUNCE_MS = 300;

export default function EditorRail({ body, pageName, isNew, cursorStore, open, onToggle, onJump, children }) {
  const debounced = useDebouncedValue(body, OUTLINE_DEBOUNCE_MS);
  const outline = useMemo(() => headingsFromMarkdown(debounced).filter((h) => h.level <= 4), [debounced]);
  const { topLine } = useEditorCursor(cursorStore);
  let active = -1;
  outline.forEach((h, i) => { if (h.line <= topLine) active = i; });

  // Below 1100px the rail is an overlay drawer: Escape dismisses it.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e) => {
      const narrow = typeof window.matchMedia === 'function' && !window.matchMedia('(min-width: 1100px)').matches;
      if (e.key === 'Escape' && narrow) onToggle();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onToggle]);

  if (!open) {
    return (
      <aside className="editor-rail collapsed" data-testid="editor-rail">
        <button type="button" className="editor-rail-toggle" aria-label="Show outline and backlinks" onClick={onToggle}>◂</button>
      </aside>
    );
  }
  return (
    <aside className="editor-rail" data-testid="editor-rail">
      <button type="button" className="editor-rail-toggle" aria-label="Hide side rail" onClick={onToggle}>▸</button>
      <section>
        <h4 className="editor-rail-heading">Outline</h4>
        {outline.length === 0 ? <p className="editor-rail-empty">No headings yet</p> : (
          <ul className="editor-rail-outline">
            {outline.map((h, i) => (
              <li key={`${h.line}-${h.text}`} data-level={h.level} style={{ paddingLeft: `${(h.level - 1) * 12}px` }}>
                <button type="button" aria-current={i === active ? 'true' : undefined} onClick={() => onJump(h.line)}>
                  {h.text || '(untitled)'}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section>
        <h4 className="editor-rail-heading">Backlinks</h4>
        {isNew ? <p className="editor-rail-empty">No backlinks yet</p>
          : <BacklinksPanel pageName={pageName} emptyText="No backlinks yet" />}
      </section>
      {children}
    </aside>
  );
}
