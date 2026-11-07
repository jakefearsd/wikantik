import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import PublishDialog from './PublishDialog.jsx';

const summary = {
  basePageVersion: 7,
  version: 3,
  unpublished: true,
  contributors: ['Ana', 'Raj'],
  lastEditAt: '2026-11-07T10:15:00Z',
  diff: '<div class="diff"><del>Hello world</del><ins>Hello world!</ins></div>',
  metaChanges: [
    { field: 'status', before: null, after: 'draft' },
    { field: 'tags', before: ['a'], after: ['a', 'c'] },
  ],
};

describe('PublishDialog', () => {
  it('theDialogShowsDiffMetaContributorsAndTheStillTypingWarning', () => {
    const onChangeNote = vi.fn();
    const onConfirm = vi.fn();
    render(<PublishDialog isOpen summary={summary} unsynced={['Raj']} newerEdits={0} changeNote="tidy" onChangeNote={onChangeNote}
      onConfirm={onConfirm} onCancel={() => {}} />);
    const dialog = screen.getByTestId('publish-dialog');
    // the server's HTML diff, in the compare page's container
    const diff = within(dialog).getByTestId('diff-html');
    expect(diff.querySelector('ins').textContent).toBe('Hello world!');
    const table = within(dialog).getByRole('table');
    const rows = within(table).getAllByRole('row').slice(1).map((r) => [...r.querySelectorAll('td')].map((td) => td.textContent));
    expect(rows).toEqual([['status', '—', 'draft'], ['tags', 'a', 'a, c']]);
    expect(within(dialog).getByText('Ana and Raj')).toBeTruthy();
    expect(within(dialog).getByTestId('publish-dialog-unsynced').textContent).toContain('Still typing: Raj');
    const note = within(dialog).getByLabelText('Change note');
    expect(note.value).toBe('tidy');
    fireEvent.change(note, { target: { value: 'tidy up' } });
    expect(onChangeNote).toHaveBeenCalledWith('tidy up');
    fireEvent.click(within(dialog).getByTestId('publish-dialog-confirm'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(within(dialog).queryByTestId('publish-dialog-newer')).toBeNull();
  });

  it('says there are no text changes when the diff is empty, and leaves the warning out with nobody typing', () => {
    render(<PublishDialog isOpen summary={{ ...summary, diff: '', metaChanges: [] }} unsynced={[]} onConfirm={() => {}} onCancel={() => {}} />);
    const dialog = screen.getByTestId('publish-dialog');
    expect(within(dialog).getByText('No text changes')).toBeTruthy();
    expect(within(dialog).queryByTestId('diff-html')).toBeNull();
    expect(within(dialog).queryByRole('table')).toBeNull();
    expect(within(dialog).queryByTestId('publish-dialog-unsynced')).toBeNull();
  });

  it('viewModeHasNoConfirm', () => {
    const onCancel = vi.fn();
    render(<PublishDialog isOpen mode="view" summary={summary} unsynced={[]} onCancel={onCancel} />);
    const dialog = screen.getByTestId('publish-dialog');
    expect(within(dialog).queryByTestId('publish-dialog-confirm')).toBeNull();
    expect(within(dialog).queryByLabelText('Change note')).toBeNull();
    expect(within(dialog).getByTestId('diff-html')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('newerEditsAreAnnounced', () => {
    const { rerender } = render(<PublishDialog isOpen summary={summary} newerEdits={3} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByTestId('publish-dialog-newer').textContent).toBe('3 newer edits will be included');
    rerender(<PublishDialog isOpen summary={summary} newerEdits={1} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByTestId('publish-dialog-newer').textContent).toBe('1 newer edit will be included');
  });

  it('busy disables confirm and the note; closed renders nothing', () => {
    const { rerender } = render(<PublishDialog isOpen busy summary={summary} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.getByTestId('publish-dialog-confirm').disabled).toBe(true);
    expect(screen.getByLabelText('Change note').disabled).toBe(true);
    rerender(<PublishDialog isOpen={false} summary={summary} onConfirm={() => {}} onCancel={() => {}} />);
    expect(screen.queryByTestId('publish-dialog')).toBeNull();
  });

  it('a hostile field value or contributor name is shown as text, never as markup', () => {
    const hostile = {
      ...summary,
      contributors: ['<img src=x onerror=alert(1)>'],
      metaChanges: [{ field: '<b>f</b>', before: '<i>x</i>', after: { a: '<script>' } }],
    };
    render(<PublishDialog isOpen summary={hostile} onConfirm={() => {}} onCancel={() => {}} />);
    const dialog = screen.getByTestId('publish-dialog');
    expect(dialog.querySelector('img, b, i, script')).toBeNull();
    expect(within(dialog).getByText('<b>f</b>')).toBeTruthy();
  });
});
