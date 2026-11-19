import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// jsdom cannot lay anything out, so the contract is asserted on the rule text. A bare `1fr` track is
// `minmax(auto, 1fr)`: it never shrinks below its content's minimum width, so a wide code block, table or
// unbroken URL in the preview widened the preview column and squeezed the editor (headless Chrome, 1200 px
// window: 85 px editor beside a 1613 px preview). `minmax(0, 1fr)` keeps the halves equal; wide content
// scrolls inside its pane.
const globals = readFileSync(resolve(process.cwd(), 'src/styles/globals.css'), 'utf8');

/** The declaration block of the first rule whose selector list is exactly `selector`. */
function block(css, selector) {
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`(?:^|\\n)${esc}\\s*\\{([^}]*)\\}`).exec(css);
  return m ? m[1] : null;
}

describe('editor and side pane share the width equally', () => {
  it('the two columns cannot grow past half for wide content', () => {
    const rule = block(globals, '.editor-container');
    expect(rule).not.toBeNull();
    expect(rule).toMatch(/grid-template-columns:\s*minmax\(0,\s*1fr\)\s+minmax\(0,\s*1fr\);/);
  });

  it('a pane may be narrower than its content', () => {
    expect(block(globals, '.editor-pane')).toMatch(/min-width:\s*0;/);
  });

  it('wide content in the preview scrolls inside it', () => {
    expect(block(globals, '.editor-preview')).toMatch(/overflow-x:\s*auto;/);
  });
});

describe('the editor preview', () => {
  // A long URL or unbroken word in a table cell must break inside the cell, so a table never makes the whole
  // preview scroll sideways.
  it('table cells break long words instead of widening the table', () => {
    expect(block(globals, '.editor-preview th, .editor-preview td')).toMatch(/overflow-wrap:\s*anywhere;/);
  });

  // Safari draws no box-shadow on a table row, so the caret row's accent bar is drawn on its first cell.
  it('the caret row drops the row shadow that Safari cannot draw', () => {
    expect(block(globals, '.editor-preview tr.editor-preview-caret-block')).toMatch(/box-shadow:\s*none;/);
  });

  it('the caret row accent bar is drawn on its first cell', () => {
    expect(block(globals, '.editor-preview tr.editor-preview-caret-block > :first-child'))
      .toMatch(/box-shadow:\s*inset 4px 0 0 var\(--accent\);/);
  });
});
