import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import EditorRail from './EditorRail';
import { createEditorCursorStore } from '../../utils/editorCursorStore';

vi.mock('../BacklinksPanel', () => ({
  default: ({ pageName }) => <div data-testid="backlinks-stub">backlinks:{pageName}</div>,
}));

const BODY = '# Title\n\n## Setup\n\ntext\n\n### Install\n\n##### Too deep\n';

function renderRail(props = {}) {
  const store = createEditorCursorStore();
  const onJump = vi.fn();
  const onToggle = vi.fn();
  const utils = render(<EditorRail body={BODY} pageName="P" isNew={false} cursorStore={store}
    open onToggle={onToggle} onJump={onJump} {...props} />);
  return { store, onJump, onToggle, ...utils };
}

afterEach(() => { vi.useRealTimers(); });

describe('EditorRail', () => {
  it('lists h1–h4 of the draft, indented by level, and jumps on click', () => {
    const { onJump } = renderRail();
    const items = screen.getAllByRole('listitem');
    expect(items.map((li) => li.textContent)).toEqual(['Title', 'Setup', 'Install']);
    expect(items.map((li) => li.dataset.level)).toEqual(['1', '2', '3']);
    fireEvent.click(screen.getByRole('button', { name: 'Install' }));
    expect(onJump).toHaveBeenCalledWith(7);
  });

  it('highlights the section at the editor top line', () => {
    const { store } = renderRail();
    act(() => store.set({ topLine: 5 }));
    expect(screen.getByRole('button', { name: 'Setup' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: 'Install' })).not.toHaveAttribute('aria-current');
  });

  it('shows empty states', () => {
    renderRail({ body: 'no headings', isNew: true });
    expect(screen.getByText('No headings yet')).toBeInTheDocument();
    expect(screen.getByText('No backlinks yet')).toBeInTheDocument();
    expect(screen.queryByTestId('backlinks-stub')).toBeNull();
  });

  it('renders backlinks for an existing page', () => {
    renderRail();
    expect(screen.getByTestId('backlinks-stub')).toHaveTextContent('backlinks:P');
  });

  it('collapsed: only the strip, whose button toggles', () => {
    const { onToggle } = renderRail({ open: false });
    expect(screen.queryByRole('listitem')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show outline and backlinks' }));
    expect(onToggle).toHaveBeenCalled();
  });

  it('debounces the outline by 300 ms', () => {
    vi.useFakeTimers();
    const store = createEditorCursorStore();
    const props = { pageName: 'P', isNew: false, cursorStore: store, open: true, onToggle: vi.fn(), onJump: vi.fn() };
    const { rerender } = render(<EditorRail body="# One" {...props} />);
    rerender(<EditorRail body={"# One\n\n## Two"} {...props} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    act(() => { vi.advanceTimersByTime(299); });
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    act(() => { vi.advanceTimersByTime(1); });
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });
});
