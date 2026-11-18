import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import EditorToolbar from './EditorToolbar';
import { EMPTY_FORMAT_STATE } from '../utils/formatState';

const ROW = ['Bold', 'Italic', 'Strikethrough', 'Bulleted list', 'Numbered list', 'Task list', 'Quote', 'Inline code', 'Table', 'Link'];
const INSERT_ITEMS = ['Code block', 'Mermaid diagram', 'PlantUML diagram', 'Math block', 'Callout: Note', 'Callout: Tip',
  'Callout: Info', 'Callout: Warning', 'Callout: Danger', 'Image', 'Embed a page', 'Footnote', 'Highlight', 'Comment',
  'Horizontal rule', 'Access rule'];

const button = (name) => screen.getByRole('button', { name });
const headingTrigger = () => screen.getByRole('button', { name: /^Heading:/ });
const openInsert = () => { fireEvent.click(button('Insert')); return screen.getByRole('menu', { name: 'Insert' }); };
const itemNames = (menu) => [...menu.querySelectorAll('[data-label]')].map((el) => el.dataset.label);

describe('EditorToolbar buttons', () => {
  it('has every button, by accessible name, in the layout order', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    const names = [...screen.getByRole('toolbar').querySelectorAll('[data-toolbar-item]')].map((el) => el.getAttribute('aria-label'));
    expect(names).toEqual(['Bold', 'Italic', 'Strikethrough', 'Heading: Paragraph', 'Bulleted list', 'Numbered list', 'Task list',
      'Quote', 'Inline code', 'Table', 'Link', 'Insert', 'Live preview (Ctrl+E)']);
  });

  it('the tooltip is the title plus the shortcut for this platform; the accessible name is the title alone', () => {
    render(<EditorToolbar onRun={vi.fn()} />); // jsdom is not a Mac
    expect(button('Bold')).toHaveAttribute('title', 'Bold (Ctrl+B)');
    expect(button('Italic')).toHaveAttribute('title', 'Italic (Ctrl+I)');
    expect(button('Strikethrough')).toHaveAttribute('title', 'Strikethrough (Ctrl+Shift+X)');
    expect(button('Link')).toHaveAttribute('title', 'Link (Ctrl+K)');
    expect(button('Quote')).toHaveAttribute('title', 'Quote');
  });

  it('the link button face is a glyph, not text', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    expect(button('Link').textContent).toBe('');
    expect(button('Link').querySelector('svg')).not.toBeNull();
  });

  it.each([
    ['Bold', 'format-bold'], ['Italic', 'format-italic'], ['Strikethrough', 'format-strike'],
    ['Bulleted list', 'format-list'], ['Numbered list', 'format-ordered-list'], ['Task list', 'format-task-list'],
    ['Quote', 'format-quote'], ['Inline code', 'format-code'], ['Table', 'insert-table'], ['Link', 'insert-link'],
  ])('click on %s runs %s', (name, id) => {
    const onRun = vi.fn();
    render(<EditorToolbar onRun={onRun} />);
    fireEvent.click(button(name));
    expect(onRun).toHaveBeenCalledWith(id);
  });

  it('mousedown keeps the editor focus (default prevented) but does not run the action itself', () => {
    const onRun = vi.fn();
    render(<EditorToolbar onRun={onRun} />);
    for (const btn of screen.getByRole('toolbar').querySelectorAll('button')) {
      expect(fireEvent.mouseDown(btn)).toBe(false);
    }
    expect(onRun).not.toHaveBeenCalled();
  });

  it('the Live toggle reflects the mode and runs toggle-live-preview', () => {
    const onRun = vi.fn();
    const { rerender } = render(<EditorToolbar onRun={onRun} liveMode={false} />);
    const live = button(/live preview/i);
    expect(live).toHaveAttribute('aria-pressed', 'false');
    expect(live.textContent).toBe('Live');
    fireEvent.click(live);
    expect(onRun).toHaveBeenCalledWith('toggle-live-preview');
    rerender(<EditorToolbar onRun={onRun} liveMode />);
    expect(button(/live preview/i)).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('EditorToolbar format state', () => {
  it('nothing is pressed for the empty state, and Table is not a toggle', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    for (const name of ROW.filter((n) => n !== 'Table')) expect(button(name)).toHaveAttribute('aria-pressed', 'false');
    expect(button('Table')).not.toHaveAttribute('aria-pressed');
  });

  it.each([
    [{ bold: true }, 'Bold'], [{ italic: true }, 'Italic'], [{ strike: true }, 'Strikethrough'], [{ code: true }, 'Inline code'],
    [{ link: true }, 'Link'], [{ quote: true }, 'Quote'], [{ list: 'bullet' }, 'Bulleted list'],
    [{ list: 'ordered' }, 'Numbered list'], [{ list: 'task' }, 'Task list'],
  ])('%j presses only %s', (part, name) => {
    render(<EditorToolbar onRun={vi.fn()} formatState={{ ...EMPTY_FORMAT_STATE, ...part }} />);
    const pressed = [...screen.getByRole('toolbar').querySelectorAll('[aria-pressed="true"]')].map((el) => el.getAttribute('aria-label'));
    expect(pressed).toEqual([name]);
  });

  it('the heading trigger shows the current level', () => {
    const { rerender } = render(<EditorToolbar onRun={vi.fn()} />);
    expect(headingTrigger().textContent).toContain('Paragraph');
    rerender(<EditorToolbar onRun={vi.fn()} formatState={{ ...EMPTY_FORMAT_STATE, heading: 3 }} />);
    expect(headingTrigger().textContent).toContain('Heading 3');
    expect(headingTrigger()).toHaveAttribute('aria-label', 'Heading: Heading 3');
    rerender(<EditorToolbar onRun={vi.fn()} formatState={{ ...EMPTY_FORMAT_STATE, heading: 6 }} />);
    expect(headingTrigger().textContent).toContain('Heading 6');
  });
});

describe('EditorToolbar heading menu', () => {
  const open = (formatState) => {
    const onRun = vi.fn();
    render(<EditorToolbar onRun={onRun} formatState={formatState} />);
    fireEvent.click(headingTrigger());
    return { onRun, menu: screen.getByRole('menu', { name: 'Heading' }) };
  };

  it('offers Paragraph and Heading 1-4, and a level runs its command', () => {
    const { onRun, menu } = open(EMPTY_FORMAT_STATE);
    expect(itemNames(menu)).toEqual(['Paragraph', 'Heading 1', 'Heading 2', 'Heading 3', 'Heading 4']);
    fireEvent.click(within(menu).getByRole('menuitemradio', { name: 'Heading 2' }));
    expect(onRun).toHaveBeenCalledWith('heading-2');
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('Paragraph is disabled outside a heading', () => {
    const { menu } = open(EMPTY_FORMAT_STATE);
    expect(within(menu).getByRole('menuitemradio', { name: 'Paragraph' })).toBeDisabled();
  });

  it('Paragraph runs the current level\'s command, which toggles it off; the current level is checked and focused', () => {
    const { onRun, menu } = open({ ...EMPTY_FORMAT_STATE, heading: 3 });
    const current = within(menu).getByRole('menuitemradio', { name: 'Heading 3' });
    expect(current).toHaveAttribute('aria-checked', 'true');
    expect(current).toHaveFocus();
    fireEvent.click(within(menu).getByRole('menuitemradio', { name: 'Paragraph' }));
    expect(onRun).toHaveBeenCalledWith('heading-3');
  });

  it('Paragraph is disabled in a level with no command (5, 6)', () => {
    const { menu } = open({ ...EMPTY_FORMAT_STATE, heading: 5 });
    expect(within(menu).getByRole('menuitemradio', { name: 'Paragraph' })).toBeDisabled();
  });
});

describe('EditorToolbar Insert menu', () => {
  it('is closed until its trigger is used, and lists the less common constructs', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    expect(screen.queryByRole('menu')).toBeNull();
    expect(button('Insert')).toHaveAttribute('aria-expanded', 'false');
    const menu = openInsert();
    expect(button('Insert')).toHaveAttribute('aria-expanded', 'true');
    expect(itemNames(menu)).toEqual(INSERT_ITEMS);
    expect(within(menu).getByRole('menuitem', { name: /^Code block/ })).toHaveFocus();
  });

  it('choosing "Mermaid diagram" runs insert-mermaid and closes the menu', () => {
    const onRun = vi.fn();
    render(<EditorToolbar onRun={onRun} />);
    fireEvent.click(within(openInsert()).getByRole('menuitem', { name: 'Mermaid diagram' }));
    expect(onRun).toHaveBeenCalledWith('insert-mermaid');
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it.each([
    ['Code block', 'code-block'], ['PlantUML diagram', 'insert-plantuml'], ['Math block', 'math-block'],
    ['Callout: Warning', 'callout-warning'], ['Image', 'insert-image'], ['Embed a page', 'insert-embed'],
    ['Footnote', 'insert-footnote'], ['Highlight', 'format-highlight'], ['Comment', 'toggle-comment'],
    ['Horizontal rule', 'horizontal-rule'], ['Access rule', 'insert-access-rule'],
  ])('%s runs %s', (label, id) => {
    const onRun = vi.fn();
    render(<EditorToolbar onRun={onRun} />);
    fireEvent.click(openInsert().querySelector(`[data-label="${label}"]`));
    expect(onRun).toHaveBeenCalledWith(id);
  });

  it('an item with a shortcut shows it', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    expect(openInsert().querySelector('[data-label="Highlight"]').textContent).toContain('Ctrl+Shift+H');
  });

  it('Escape closes the menu and returns focus to the trigger', () => {
    const onRun = vi.fn();
    render(<EditorToolbar onRun={onRun} />);
    openInsert();
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
    expect(button('Insert')).toHaveFocus();
    expect(onRun).not.toHaveBeenCalled();
  });

  it('Up/Down/Home/End move between the items, wrapping', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    const menu = openInsert();
    const focused = () => document.activeElement.dataset.label;
    fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
    expect(focused()).toBe('Mermaid diagram');
    fireEvent.keyDown(document.activeElement, { key: 'End' });
    expect(focused()).toBe('Access rule');
    fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
    expect(focused()).toBe('Code block');
    fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' });
    expect(focused()).toBe('Access rule');
    fireEvent.keyDown(document.activeElement, { key: 'Home' });
    expect(focused()).toBe('Code block');
    expect(menu).toBeInTheDocument();
  });

  it('ArrowDown on the trigger opens the menu; a press outside closes it', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    fireEvent.keyDown(button('Insert'), { key: 'ArrowDown' });
    expect(screen.getByRole('menu', { name: 'Insert' })).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('opening one menu closes the other', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    openInsert();
    fireEvent.mouseDown(headingTrigger());
    fireEvent.click(headingTrigger());
    expect(screen.getAllByRole('menu')).toHaveLength(1);
    expect(screen.getByRole('menu', { name: 'Heading' })).toBeInTheDocument();
  });
});

describe('EditorToolbar keyboard', () => {
  const tabStops = () => [...screen.getByRole('toolbar').querySelectorAll('[data-toolbar-item]')]
    .filter((el) => el.tabIndex === 0).map((el) => el.getAttribute('aria-label'));

  it('is one tab stop: only one item is in the tab order', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    expect(tabStops()).toEqual(['Bold']);
  });

  it('Right/Left move between the items, through the menus\' triggers, and the tab stop follows', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    button('Bold').focus();
    fireEvent.keyDown(document.activeElement, { key: 'ArrowRight' });
    expect(button('Italic')).toHaveFocus();
    expect(tabStops()).toEqual(['Italic']);
    fireEvent.keyDown(document.activeElement, { key: 'ArrowRight' });
    fireEvent.keyDown(document.activeElement, { key: 'ArrowRight' });
    expect(headingTrigger()).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' });
    expect(button('Strikethrough')).toHaveFocus();
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('Home/End jump to the ends and the arrows wrap', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    button('Quote').focus();
    fireEvent.keyDown(document.activeElement, { key: 'End' });
    expect(button(/live preview/i)).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'ArrowRight' });
    expect(button('Bold')).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' });
    expect(button(/live preview/i)).toHaveFocus();
    fireEvent.keyDown(document.activeElement, { key: 'Home' });
    expect(button('Bold')).toHaveFocus();
  });

  it('a disabled mode toggle is skipped', () => {
    render(<EditorToolbar onRun={vi.fn()} liveDisabled />);
    button('Bold').focus();
    fireEvent.keyDown(document.activeElement, { key: 'End' });
    expect(button('Insert')).toHaveFocus();
  });

  it('Tab is left alone, so it leaves the toolbar', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    button('Bold').focus();
    expect(fireEvent.keyDown(document.activeElement, { key: 'Tab' })).toBe(true);
    expect(button('Bold')).toHaveFocus();
  });
});

describe('EditorToolbar overflow', () => {
  const SIZES = [[HTMLElement.prototype, 'offsetWidth'], [HTMLElement.prototype, 'clientWidth']];
  const originals = SIZES.map(([proto, name]) => Object.getOwnPropertyDescriptor(proto, name));
  afterEach(() => {
    vi.unstubAllGlobals();
    SIZES.forEach(([proto, name], i) => {
      if (originals[i]) Object.defineProperty(proto, name, originals[i]);
      else delete proto[name];
    });
  });

  /** Every toolbar child is 32px wide and the toolbar is `width.current` wide; returns the function that reports a resize. */
  function layout(width) {
    const observers = [];
    vi.stubGlobal('ResizeObserver', class {
      constructor(cb) { this.cb = cb; }
      observe() { observers.push(this.cb); }
      disconnect() { observers.splice(observers.indexOf(this.cb), 1); }
    });
    Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get() { return 32; } });
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get() { return width.current; } });
    return () => act(() => { observers.forEach((cb) => cb()); });
  }
  const rowNames = () => [...screen.getByRole('toolbar').querySelectorAll('[data-overflow-id]')].map((el) => el.getAttribute('aria-label'));

  it('without ResizeObserver nothing moves', () => {
    render(<EditorToolbar onRun={vi.fn()} />);
    expect(rowNames()).toEqual(ROW);
  });

  it('buttons that do not fit move to the top of the Insert menu, from the right end, and come back', () => {
    const width = { current: 13 * 32 };
    const resized = layout(width);
    const onRun = vi.fn();
    render(<EditorToolbar onRun={onRun} formatState={{ ...EMPTY_FORMAT_STATE, quote: true }} />);
    resized();
    expect(rowNames()).toEqual(ROW);

    width.current = 9 * 32 + 10; // the two menus and the mode toggle stay, leaving room for six buttons
    resized();
    expect(rowNames()).toEqual(ROW.slice(0, 6));
    expect(headingTrigger()).toBeInTheDocument();
    expect(button(/live preview/i)).toBeInTheDocument();
    const menu = openInsert();
    expect(itemNames(menu)).toEqual(['Quote', 'Inline code', 'Table', 'Link', ...INSERT_ITEMS]);
    expect(within(menu).getByRole('separator')).toBeInTheDocument();
    expect(menu.querySelector('[data-label="Quote"]')).toHaveAttribute('aria-checked', 'true');
    expect(menu.querySelector('[data-label="Link"]')).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(menu.querySelector('[data-label="Table"]'));
    expect(onRun).toHaveBeenCalledWith('insert-table');

    width.current = 0; // narrower than the fixed controls: every button moves, Bold last
    resized();
    expect(rowNames()).toEqual([]);
    expect(itemNames(openInsert()).slice(0, 10)).toEqual(ROW);
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });

    width.current = 13 * 32;
    resized();
    expect(rowNames()).toEqual(ROW);
  });

  it('the tab stop falls back to the first item when its button has moved away', () => {
    const width = { current: 13 * 32 };
    const resized = layout(width);
    render(<EditorToolbar onRun={vi.fn()} />);
    resized();
    act(() => button('Link').focus());
    width.current = 9 * 32;
    resized();
    const stops = [...screen.getByRole('toolbar').querySelectorAll('[data-toolbar-item]')].filter((el) => el.tabIndex === 0);
    expect(stops.map((el) => el.getAttribute('aria-label'))).toEqual(['Bold']);
  });
});
