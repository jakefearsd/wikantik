import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { waitFor, fireEvent } from '@testing-library/react';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';

vi.mock('../fences/mermaidRenderer.js', () => ({
  draw: vi.fn(async (host) => { host.innerHTML = '<svg viewBox="0 0 100 100"><title>drawn</title></svg>'; }),
}));
import * as rendererModule from '../fences/mermaidRenderer.js';
// PlantUML is drawn by the server: its renderer is stood in for the same way (a drawn SVG in the host).
const serverRenderer = { draw: vi.fn(async (host) => { host.innerHTML = '<svg viewBox="0 0 100 100"><title>drawn</title></svg>'; }) };
import { FENCE_RENDERERS } from '../fences/registry';
import { editorMarkdownConfig } from '../editorMarkdown';
import { livePreview, setLivePreview } from './index';

const SOURCES = { mermaid: 'flowchart TD\n  A-->B\n', plantuml: '@startuml\nAlice -> Bob: hi\n@enduml\n' };
let view;
let DOC;
function mountWith(language) {
  DOC = `Above\n\n\`\`\`${language}\n${SOURCES[language]}\`\`\`\n\nBelow`;
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  view = new EditorView({ parent, state: EditorState.create({ doc: DOC, extensions: [markdown(editorMarkdownConfig), livePreview({ getContext: () => ({}) })] }) });
  ensureSyntaxTree(view.state, view.state.doc.length, 5000);
  view.dispatch({ selection: { anchor: 0 } });
  setLivePreview(view, true);
}
beforeEach(() => {
  vi.spyOn(FENCE_RENDERERS.mermaid, 'load').mockImplementation(async () => rendererModule);
  vi.spyOn(FENCE_RENDERERS.plantuml, 'load').mockImplementation(async () => serverRenderer);
});
afterEach(() => { view?.destroy(); view = null; document.body.innerHTML = ''; vi.restoreAllMocks(); });

const block = () => view.dom.querySelector('.cm-lp-fence-block');
const button = async (action) => {
  await waitFor(() => expect(block().querySelector(`.wk-fence-toolbar [data-action="${action}"]`)).not.toBeNull());
  return block().querySelector(`[data-action="${action}"]`);
};
/** A real click: the mousedown the reveal listens for, then the click the toolbar listens for. */
const click = (el) => { fireEvent.mouseDown(el); fireEvent.mouseUp(el); fireEvent.click(el); };

describe.each(['mermaid', 'plantuml'])('the toolbar of a %s diagram drawn in Rich mode does what the preview\'s does', (language) => {
  beforeEach(() => mountWith(language));

  it('Show source keeps the drawing and opens the diagram\'s text under it; Hide source closes it', async () => {
    const toggle = await button('source');
    click(toggle);
    expect(view.state.selection.main.head).toBe(0); // the caret stays where it was: no reveal
    expect(block()).not.toBeNull();
    expect(block().querySelector('.wk-fence-drawing svg')).not.toBeNull();
    const pre = block().querySelector('pre.wk-fence-source');
    expect(pre.classList.contains('wk-fence-source-open')).toBe(true);
    expect(pre.textContent).toBe(SOURCES[language]);
    expect(toggle.textContent).toBe('Hide source');
    click(toggle);
    expect(pre.classList.contains('wk-fence-source-open')).toBe(false);
    expect(toggle.textContent).toBe('Show source');
  });

  it('Expand opens the full-size view without revealing the source', async () => {
    const expand = await button('expand');
    expand.hidden = false;
    click(expand);
    expect(view.state.selection.main.head).toBe(0);
    expect(block().querySelector('dialog.wk-fence-dialog')).not.toBeNull();
  });

  it('a click on the drawing itself still opens the source for editing', async () => {
    await button('source');
    fireEvent.mouseDown(block().querySelector('.wk-fence-drawing'));
    expect(view.state.selection.main.head).toBe(DOC.indexOf(SOURCES[language]));
    expect(block()).toBeNull();
  });
});
