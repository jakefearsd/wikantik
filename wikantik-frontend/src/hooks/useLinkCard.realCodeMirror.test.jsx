/**
 * useLinkCard wired as PageEditor wires it — the real CodeEditor, the hook, the card — in a host small enough to
 * remount the editor (what a shared draft's resync does) and to keep hold of a card's callbacks after it is gone.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
// eslint-disable-next-line testing-library/no-manual-cleanup -- the editor must be gone before the next test's selection events
import { render, act, screen, fireEvent, cleanup } from '@testing-library/react';
import { useEffect, useRef } from 'react';
import { EditorView } from '@codemirror/view';
import '../test/asyncSelectionChange';
import { parseFully } from '../test/fullParse';
import CodeEditor from '../components/CodeEditor';
import LinkCard from '../components/editor/LinkCard';
import { requestLinkCard, closeLinkCard, linkCardField } from '../utils/livePreview/linkCard';
import { useLinkCard } from './useLinkCard';

const DOC = '[[A|x]] and [[B|y]] end';
const NO_ANSWER = () => new Promise(() => {});
const seen = { cardProps: null };

function Host({ editorKey = 'one' }) {
  const editorRef = useRef(null);
  const linkCard = useLinkCard({ editorRef, live: true, suggestPages: NO_ANSWER });
  useEffect(() => { seen.cardProps = linkCard.cardProps; });
  return (
    <>
      <CodeEditor key={editorKey} ref={editorRef} value={DOC} onChange={() => {}} livePreview
        livePreviewContext={{ pageName: 'Here', attachments: [] }} onOpenLinkCard={linkCard.onOpenLinkCard} />
      {linkCard.cardProps && <LinkCard {...linkCard.cardProps} />}
    </>
  );
}

function mount() {
  const utils = render(<Host />);
  const view = EditorView.findFromDOM(utils.container.querySelector('.cm-editor'));
  parseFully(view);
  return { view, ...utils };
}
const openOn = (view, pos) => act(() => { expect(requestLinkCard(view, pos)).toBe(true); });
const card = () => screen.queryByRole('dialog', { name: 'Edit link' });
const docOf = (view) => view.state.doc.toString();

afterEach(() => {
  document.getSelection()?.removeAllRanges();
  cleanup();
  seen.cardProps = null;
});

describe('a card callback that outlived its card', () => {
  it('writes nothing once another card is open: not a commit, not a removal, and it does not close the other', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { view } = mount();
    openOn(view, 1);
    const stale = seen.cardProps;
    act(() => { view.dispatch({ effects: closeLinkCard.of(null) }); });
    openOn(view, 13);
    const open = view.state.field(linkCardField);
    act(() => { stale.onCommit({ target: 'Z' }); });
    act(() => { stale.onCommit({ alias: 'late' }, { focusLeft: true }); });
    act(() => { stale.onRemove(); });
    act(() => { stale.onClose(); });
    expect(docOf(view)).toBe(DOC);
    expect(view.state.field(linkCardField)).toBe(open);
    expect(screen.getByLabelText('Page').value).toBe('B');
    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it('writes nothing once its own card is closed', () => {
    const { view } = mount();
    openOn(view, 1);
    const stale = seen.cardProps;
    act(() => { view.dispatch({ effects: closeLinkCard.of(null) }); });
    act(() => { stale.onCommit({ target: 'Z' }); });
    expect(docOf(view)).toBe(DOC);
    expect(card()).toBe(null);
  });

  it('the card that is open does write', () => {
    const { view } = mount();
    openOn(view, 13);
    act(() => { seen.cardProps.onCommit({ target: 'Z' }); });
    expect(docOf(view)).toBe('[[A|x]] and [[Z|y]] end');
    expect(card()).toBe(null);
  });
});

describe('the editor remounting under an open card (a shared draft resyncing)', () => {
  it('closes the card instead of leaving it on screen over an editor that is gone', () => {
    const { view, rerender, container } = mount();
    openOn(view, 1);
    fireEvent.change(screen.getByLabelText('Text'), { target: { value: 'typed' } });
    expect(card()).not.toBe(null);
    rerender(<Host editorKey="two" />);
    expect(card()).toBe(null);
    const fresh = EditorView.findFromDOM(container.querySelector('.cm-editor'));
    expect(fresh).not.toBe(view);
    expect(docOf(fresh)).toBe(DOC);
    expect(fresh.state.field(linkCardField)).toBe(null);
  });
});
