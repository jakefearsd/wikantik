import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { fullyParsed } from '../../test/fullParse';
import { editorMarkdownConfig } from '../../utils/editorMarkdown';
import { cardModelAt } from '../../utils/livePreview/linkCard';
import LinkCard from './LinkCard';

/** The model the editor would hand the card for the first link of `source`. */
function modelOf(source) {
  const state = fullyParsed(EditorState.create({ doc: source, extensions: [markdown(editorMarkdownConfig)] }));
  return cardModelAt(state, source.search(/!?\[/) + 1);
}

/** A list that never arrives: the page field asks as it takes the focus, and a late answer would update outside act(). */
const NO_ANSWER = () => new Promise(() => {});

function show(source, props = {}) {
  const handlers = { onCommit: vi.fn(), onRemove: vi.fn(), onOpen: vi.fn(), onClose: vi.fn() };
  render(<LinkCard model={modelOf(source)} anchorRect={null} suggestPages={NO_ANSWER} {...handlers} {...props} />);
  return handlers;
}
const labels = () => [...screen.getByRole('dialog').querySelectorAll('label')].map((l) => l.textContent);
const field = (name) => screen.getByLabelText(name);
const edit = (name, value) => fireEvent.change(field(name), { target: { value } });
const enter = (name) => fireEvent.keyDown(field(name), { key: 'Enter' });

describe('LinkCard fields', () => {
  it('is a dialog named "Edit link"', () => {
    show('[[P|a]]');
    expect(screen.getByRole('dialog', { name: 'Edit link' })).toBeTruthy();
  });
  it.each([
    ['[[P#H|a]]', ['Page', 'Heading', 'Text']],
    ['![[P#H]]', ['Page', 'Heading']],
    ['![[pic.png]]', ['Page', 'Heading', 'Size']],
    ['![[Docs/pic.png|300x200]]', ['Page', 'Heading', 'Size']],
    ['[text](https://e.org)', ['URL', 'Text']],
    ['![alt](pic.png)', ['Source', 'Alt text']],
  ])('%s shows %j', (source, expected) => {
    show(source);
    expect(labels()).toEqual(expected);
  });
  it('starts from what the link holds', () => {
    show('![[Docs/pic.png|300x200]]');
    expect(field('Page').value).toBe('Docs/pic.png');
    expect(field('Size').value).toBe('300x200');
  });
  it('the page field is a combobox fed by suggestPages', async () => {
    const suggestPages = vi.fn(() => Promise.resolve(['Q', 'Other']));
    show('[[P]]', { suggestPages });
    expect(field('Page').getAttribute('role')).toBe('combobox');
    fireEvent.focus(field('Page'));
    expect(await screen.findByRole('option', { name: 'Other' })).toBeTruthy();
    expect(suggestPages).toHaveBeenCalledWith('P');
  });
  it('a link text holding markup of its own is shown but cannot be edited', () => {
    show('[**bold** x](https://e.org)');
    expect(field('Text').readOnly).toBe(true);
    expect(field('Text').value).toBe('**bold** x');
    expect(field('URL').readOnly).toBe(false);
  });
  it('takes the focus, on its first field', () => {
    show('[text](https://e.org)');
    expect(document.activeElement).toBe(field('URL'));
  });
});

describe('LinkCard commit', () => {
  it('editing Page then Enter commits that one field, once', () => {
    const h = show('[[P|a]]');
    edit('Page', 'Q');
    enter('Page');
    expect(h.onCommit).toHaveBeenCalledTimes(1);
    expect(h.onCommit).toHaveBeenCalledWith({ target: 'Q' });
    expect(h.onClose).not.toHaveBeenCalled();
  });
  it('commits every changed field together, an emptied optional one as null', () => {
    const h = show('[[P#H|a]]');
    edit('Heading', '');
    edit('Text', 'shown');
    enter('Text');
    expect(h.onCommit).toHaveBeenCalledWith({ heading: null, alias: 'shown' });
  });
  it('a Markdown link commits url and text; an image its alt text', () => {
    const link = show('[text](https://e.org)');
    edit('URL', 'https://x.org');
    edit('Text', 'more');
    enter('URL');
    expect(link.onCommit).toHaveBeenCalledWith({ url: 'https://x.org', text: 'more' });
  });
  it('an image commits its alt text as alt', () => {
    const h = show('![alt](pic.png)');
    edit('Alt text', 'a cat');
    enter('Alt text');
    expect(h.onCommit).toHaveBeenCalledWith({ alt: 'a cat' });
  });
  it('Enter with nothing edited closes without a commit', () => {
    const h = show('[[P|a]]');
    enter('Text');
    expect(h.onCommit).not.toHaveBeenCalled();
    expect(h.onClose).toHaveBeenCalledTimes(1);
  });
  it('a field edited back to what it held is not a change', () => {
    const h = show('[[P|a]]');
    edit('Text', 'b');
    edit('Text', 'a');
    enter('Text');
    expect(h.onCommit).not.toHaveBeenCalled();
    expect(h.onClose).toHaveBeenCalledTimes(1);
  });
  it('Escape discards: only onClose', () => {
    const h = show('[[P|a]]');
    edit('Text', 'changed');
    fireEvent.keyDown(field('Text'), { key: 'Escape' });
    expect(h.onClose).toHaveBeenCalledTimes(1);
    expect(h.onCommit).not.toHaveBeenCalled();
    expect(h.onRemove).not.toHaveBeenCalled();
  });
  it('an invalid alias shows the error and does not commit', () => {
    const h = show('[[P|a]]');
    edit('Text', 'a]b');
    enter('Text');
    expect(screen.getByRole('alert').textContent).toBe('The display text cannot contain a square bracket.');
    expect(h.onCommit).not.toHaveBeenCalled();
    expect(h.onClose).not.toHaveBeenCalled();
    edit('Text', 'ab');
    enter('Text');
    expect(h.onCommit).toHaveBeenCalledWith({ alias: 'ab' });
  });
  it('an invalid size is refused with the reason', () => {
    const h = show('![[pic.png]]');
    edit('Size', 'wide');
    enter('Size');
    expect(screen.getByRole('alert').textContent).toMatch(/width in pixels/);
    expect(h.onCommit).not.toHaveBeenCalled();
  });
  it('focus leaving the card commits changed fields', () => {
    const h = show('[text](https://e.org)');
    edit('Text', 'more');
    fireEvent.blur(field('Text'), { relatedTarget: document.body });
    expect(h.onCommit).toHaveBeenCalledTimes(1);
    expect(h.onCommit).toHaveBeenCalledWith({ text: 'more' }, { focusLeft: true }); // the host leaves the focus where it went
  });
  it('focus leaving the card with nothing changed closes it', () => {
    const h = show('[text](https://e.org)');
    fireEvent.blur(field('Text'), { relatedTarget: document.body });
    expect(h.onCommit).not.toHaveBeenCalled();
    expect(h.onClose).toHaveBeenCalledTimes(1);
    expect(h.onClose).toHaveBeenCalledWith({ focusLeft: true });
  });
  it('focus leaving with a refused value keeps the card, and the focus comes back to the field', () => {
    const h = show('[[P|a]]');
    edit('Text', 'a]b');
    document.body.focus();
    fireEvent.blur(field('Text'), { relatedTarget: document.body });
    expect(screen.getByRole('alert')).toBeTruthy();
    expect(h.onCommit).not.toHaveBeenCalled();
    expect(h.onClose).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(field('Text'));
  });
  it('another window taking the focus neither commits nor closes', () => {
    const h = show('[text](https://e.org)');
    edit('Text', 'more');
    const hasFocus = vi.spyOn(document, 'hasFocus').mockReturnValue(false);
    fireEvent.blur(field('Text'));
    hasFocus.mockRestore();
    expect(h.onCommit).not.toHaveBeenCalled();
    expect(h.onClose).not.toHaveBeenCalled();
  });
  it('focus moving between the card\'s own fields commits nothing', () => {
    const h = show('[text](https://e.org)');
    edit('Text', 'more');
    fireEvent.blur(field('Text'), { relatedTarget: field('URL') });
    expect(h.onCommit).not.toHaveBeenCalled();
    expect(h.onClose).not.toHaveBeenCalled();
  });
  it('commits at most once, whatever follows', () => {
    const h = show('[text](https://e.org)');
    edit('Text', 'more');
    enter('Text');
    fireEvent.blur(field('Text'), { relatedTarget: document.body });
    enter('Text');
    expect(h.onCommit).toHaveBeenCalledTimes(1);
    expect(h.onClose).not.toHaveBeenCalled();
  });
  it('Enter while an input method is composing is the composition\'s', () => {
    const h = show('[text](https://e.org)');
    edit('Text', 'more');
    fireEvent.keyDown(field('Text'), { key: 'Enter', isComposing: true });
    expect(h.onCommit).not.toHaveBeenCalled();
  });
});

describe('LinkCard actions', () => {
  it('Remove and Open report, without a commit', () => {
    const h = show('[[P|a]]');
    fireEvent.click(screen.getByRole('button', { name: 'Open' }));
    expect(h.onOpen).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    expect(h.onRemove).toHaveBeenCalledTimes(1);
    expect(h.onCommit).not.toHaveBeenCalled();
  });
  it('Open is disabled when there is nowhere to go', () => {
    show('[[#Here]]', { onOpen: null });
    expect(screen.getByRole('button', { name: 'Open' }).disabled).toBe(true);
  });
  it('Tab from the last control wraps to the first (the focus is trapped)', () => {
    show('[text](https://e.org)');
    const remove = screen.getByRole('button', { name: 'Remove' });
    remove.focus();
    fireEvent.keyDown(remove, { key: 'Tab' });
    expect(document.activeElement).toBe(field('URL'));
    fireEvent.keyDown(field('URL'), { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(remove);
  });
  it('Escape is called with nothing: the host returns the focus to the editor', () => {
    const h = show('[[P|a]]');
    fireEvent.keyDown(field('Page'), { key: 'Escape' });
    expect(h.onClose).toHaveBeenCalledWith();
  });
});
