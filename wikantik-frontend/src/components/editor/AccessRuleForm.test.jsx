import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import AccessRuleForm from './AccessRuleForm';

const insertButton = () => screen.getByRole('button', { name: 'Insert' });
const addName = (name) => {
  const field = screen.getByLabelText('Add a group, role or user…');
  fireEvent.change(field, { target: { value: name } });
  fireEvent.keyDown(field, { key: 'Enter' });
};

describe('AccessRuleForm', () => {
  it('renders nothing while closed', () => {
    render(<AccessRuleForm open={false} onClose={vi.fn()} onInsert={vi.fn()} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('offers the seven permissions, "view" first and selected', () => {
    render(<AccessRuleForm open onClose={vi.fn()} onInsert={vi.fn()} />);
    const select = screen.getByLabelText('Permission');
    expect([...select.options].map((o) => o.value)).toEqual(['view', 'comment', 'edit', 'modify', 'upload', 'rename', 'delete']);
    expect(select.value).toBe('view');
  });

  it('keeps Insert disabled until a name is entered, and again once the last name is removed', () => {
    render(<AccessRuleForm open onClose={vi.fn()} onInsert={vi.fn()} />);
    expect(insertButton().disabled).toBe(true);
    addName('Editors');
    expect(insertButton().disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    expect(insertButton().disabled).toBe(true);
  });

  it('submitting hands the chosen permission and names to onInsert', () => {
    const onInsert = vi.fn();
    render(<AccessRuleForm open onClose={vi.fn()} onInsert={onInsert} />);
    fireEvent.change(screen.getByLabelText('Permission'), { target: { value: 'edit' } });
    addName('Editors');
    fireEvent.click(insertButton());
    expect(onInsert).toHaveBeenCalledTimes(1);
    expect(onInsert).toHaveBeenCalledWith({ permission: 'edit', principals: ['Editors'] });
  });

  it('Enter in the empty name field does not submit a rule without names', () => {
    const onInsert = vi.fn();
    render(<AccessRuleForm open onClose={vi.fn()} onInsert={onInsert} />);
    fireEvent.submit(screen.getByRole('dialog').querySelector('form'));
    expect(onInsert).not.toHaveBeenCalled();
  });

  it('Cancel closes without inserting', () => {
    const onClose = vi.fn();
    const onInsert = vi.fn();
    render(<AccessRuleForm open onClose={onClose} onInsert={onInsert} />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(onInsert).not.toHaveBeenCalled();
  });

  it('starts empty again each time it opens', () => {
    const { rerender } = render(<AccessRuleForm open onClose={vi.fn()} onInsert={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Permission'), { target: { value: 'delete' } });
    addName('Editors');
    rerender(<AccessRuleForm open={false} onClose={vi.fn()} onInsert={vi.fn()} />);
    rerender(<AccessRuleForm open onClose={vi.fn()} onInsert={vi.fn()} />);
    expect(screen.getByLabelText('Permission').value).toBe('view');
    expect(insertButton().disabled).toBe(true);
  });
});
