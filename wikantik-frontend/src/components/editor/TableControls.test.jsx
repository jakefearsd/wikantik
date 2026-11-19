import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import TableControls from './TableControls';

const button = (name) => screen.getByRole('button', { name });
const openMenu = () => { fireEvent.click(button('More table actions')); return screen.getByRole('menu', { name: 'Table' }); };
const mount = (props = {}) => {
  const onRun = vi.fn();
  render(<TableControls onRun={onRun} canDeleteRow canDeleteColumn {...props} />);
  return onRun;
};

describe('TableControls', () => {
  it('is a toolbar with a button for a row, one for a column, and a menu for the rest', () => {
    mount();
    expect(screen.getByRole('toolbar', { name: 'Table' })).toBeInTheDocument();
    expect(button('Insert row below')).toHaveTextContent('+ row');
    expect(button('Insert column right')).toHaveTextContent('+ col');
    expect(button('More table actions')).toHaveAttribute('aria-haspopup', 'menu');
  });

  it('runs the command of the button pressed', () => {
    const onRun = mount();
    fireEvent.click(button('Insert row below'));
    fireEvent.click(button('Insert column right'));
    expect(onRun.mock.calls).toEqual([['table-row-below'], ['table-column-right']]);
  });

  it('offers every other table command in the menu, and runs the one chosen', () => {
    const onRun = mount();
    const menu = openMenu();
    const names = [...menu.querySelectorAll('[data-label]')].map((el) => el.getAttribute('aria-label'));
    expect(names).toEqual(['Insert row above', 'Insert column left', 'Move column left', 'Move column right',
      'Align column left', 'Align column center', 'Align column right', 'Remove column alignment', 'Delete row', 'Delete column']);
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Move column left' }));
    expect(onRun).toHaveBeenCalledWith('table-move-column-left');
    expect(screen.queryByRole('menu')).toBe(null);
  });

  it('disables "Delete row" when the row cannot be deleted, and "Delete column" likewise', () => {
    mount({ canDeleteRow: false });
    let menu = openMenu();
    expect(within(menu).getByRole('menuitem', { name: 'Delete row' })).toBeDisabled();
    expect(within(menu).getByRole('menuitem', { name: 'Delete column' })).toBeEnabled();
    fireEvent.keyDown(menu, { key: 'Escape' });
    mount({ canDeleteColumn: false });
    fireEvent.click(screen.getAllByRole('button', { name: 'More table actions' })[1]);
    menu = screen.getByRole('menu', { name: 'Table' });
    expect(within(menu).getByRole('menuitem', { name: 'Delete column' })).toBeDisabled();
    expect(within(menu).getByRole('menuitem', { name: 'Delete row' })).toBeEnabled();
  });

  it('disables what the caret\'s cell rules out, and checks the column\'s alignment', () => {
    mount({ canInsertRowAbove: false, canMoveLeft: false, canMoveRight: false, align: 'center' });
    const menu = openMenu();
    for (const name of ['Insert row above', 'Move column left', 'Move column right']) {
      expect(within(menu).getByRole('menuitem', { name })).toBeDisabled();
    }
    expect(within(menu).getByRole('menuitemradio', { name: 'Align column center' })).toHaveAttribute('aria-checked', 'true');
    expect(within(menu).getByRole('menuitemradio', { name: 'Remove column alignment' })).toHaveAttribute('aria-checked', 'false');
  });

  it('is one tab stop: the arrow keys move between its buttons, Escape leaves it', () => {
    const onLeave = vi.fn();
    mount({ onLeave });
    const [row, col, more] = [button('Insert row below'), button('Insert column right'), button('More table actions')];
    expect([row, col, more].map((el) => el.tabIndex)).toEqual([0, -1, -1]);
    row.focus();
    fireEvent.keyDown(row, { key: 'ArrowRight' });
    expect(col).toHaveFocus();
    fireEvent.keyDown(col, { key: 'ArrowRight' });
    expect(more).toHaveFocus();
    fireEvent.keyDown(more, { key: 'ArrowRight' });
    expect(row).toHaveFocus();
    fireEvent.keyDown(row, { key: 'ArrowLeft' });
    expect(more).toHaveFocus();
    fireEvent.keyDown(more, { key: 'Escape' });
    expect(onLeave).toHaveBeenCalledTimes(1);
  });

  it('does not take the focus from the editor on a mouse press', () => {
    mount();
    expect(fireEvent.mouseDown(button('Insert row below'))).toBe(false); // default prevented
  });
});
