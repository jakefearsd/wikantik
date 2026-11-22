import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../api/work', () => ({ listReportable: vi.fn(), submitIntake: vi.fn(), uploadAttachment: vi.fn() }));
import { listReportable, submitIntake, uploadAttachment } from '../../api/work';
import WorkSubmit from './WorkSubmit';

const PROJECTS = [{ key: 'PLAT', name: 'Platform', role: 'reporter' }, { key: 'DOCS', name: 'Documentation', role: 'viewer' }];

function setup() {
  return render(
    <MemoryRouter initialEntries={['/work/submit']}>
      <Routes>
        <Route path="/work/submit" element={<WorkSubmit />} />
        <Route path="/work/item/:key" element={<div>ITEM PAGE</div>} />
        <Route path="/work" element={<div>HOME</div>} />
      </Routes>
    </MemoryRouter>);
}
const file = (name) => new File(['x'], name, { type: 'text/plain' });
const type = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const send = () => fireEvent.click(screen.getByRole('button', { name: 'File request' }));

beforeEach(() => {
  vi.clearAllMocks();
  listReportable.mockResolvedValue(PROJECTS);
  submitIntake.mockResolvedValue({ key: 'PLAT-9' });
  uploadAttachment.mockResolvedValue({});
});

describe('WorkSubmit', () => {
  it('files a bug and lands on its page', async () => {
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    expect(screen.getByLabelText('Project')).toHaveValue('PLAT');
    type('Type', 'bug');
    type('Title', '  It breaks  ');
    type('Details', 'Steps here');
    send();
    expect(await screen.findByText('ITEM PAGE')).toBeInTheDocument();
    expect(submitIntake).toHaveBeenCalledWith({ projectKey: 'PLAT', level: 'story', type: 'bug', title: 'It breaks', body: 'Steps here' });
    expect(uploadAttachment).not.toHaveBeenCalled();
  });

  it('uploads chosen files after filing', async () => {
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    type('Title', 'With files');
    const a = file('a.txt'); const b = file('b.txt');
    fireEvent.change(screen.getByLabelText('Files'), { target: { files: [a, b] } });
    send();
    expect(await screen.findByText('ITEM PAGE')).toBeInTheDocument();
    expect(uploadAttachment.mock.calls.map((c) => [c[0], c[1].name])).toEqual([['PLAT-9', 'a.txt'], ['PLAT-9', 'b.txt']]);
    expect(submitIntake.mock.invocationCallOrder[0]).toBeLessThan(uploadAttachment.mock.invocationCallOrder[0]);
  });

  it('a failed upload says so with the item link and retries only the failed file', async () => {
    uploadAttachment.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 413 }));
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    type('Title', 'Mixed');
    fireEvent.change(screen.getByLabelText('Files'), { target: { files: [file('big.bin'), file('ok.txt')] } });
    send();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Your request was filed, but a file did not upload.');
    expect(screen.getAllByRole('link', { name: /PLAT-9/ })[0]).toHaveAttribute('href', '/work/item/PLAT-9');
    expect(screen.queryByText('ITEM PAGE')).toBeNull();
    expect(submitIntake).toHaveBeenCalledTimes(1);
    expect(uploadAttachment).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole('button', { name: 'Retry the upload' }));
    expect(await screen.findByText('ITEM PAGE')).toBeInTheDocument();
    expect(uploadAttachment).toHaveBeenCalledTimes(3);
    expect(uploadAttachment.mock.calls[2][1].name).toBe('big.bin');
    expect(submitIntake).toHaveBeenCalledTimes(1);
  });

  it('says so when there is no project to file into', async () => {
    listReportable.mockResolvedValue([]);
    setup();
    expect(await screen.findByText('You cannot file work into any project.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('lists the projects by the names the server returns', async () => {
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    expect([...screen.getByLabelText('Project').querySelectorAll('option')].map((o) => o.textContent)).toEqual(['Platform', 'Documentation']);
  });

  it('validates the title before sending and keeps what was typed', async () => {
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    type('Details', 'kept');
    send();
    expect(await screen.findByText('Enter a title.')).toBeInTheDocument();
    expect(submitIntake).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Details')).toHaveValue('kept');
  });

  it('shows the route\'s refusal beside the field and keeps everything typed', async () => {
    submitIntake.mockRejectedValue(Object.assign(new Error('Intake files a story or a task.'), { status: 400, body: { field: 'level', message: 'Intake files a story or a task.' } }));
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    type('Title', 'T'); type('Details', 'Body text');
    send();
    expect(await screen.findByRole('alert')).toHaveTextContent('Intake files a story or a task.');
    expect(screen.getByLabelText('Title')).toHaveValue('T');
    expect(screen.getByLabelText('Details')).toHaveValue('Body text');
    expect(screen.getByRole('button', { name: 'File request' })).not.toBeDisabled();
  });

  it('blocks a title over 200 characters with a plain message and shows a count near the limit', async () => {
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    type('Title', 'a'.repeat(185));
    expect(screen.getByText('185 / 200 characters')).toBeInTheDocument();
    type('Title', 'a'.repeat(201));
    expect(screen.getByRole('alert')).toHaveTextContent('This title is 1 characters over the 200-character limit.');
    expect(screen.getByRole('button', { name: 'File request' })).toBeDisabled();
    send();
    expect(submitIntake).not.toHaveBeenCalled();
    type('Title', 'a'.repeat(200));
    expect(screen.getByRole('button', { name: 'File request' })).not.toBeDisabled();
  });

  it('blocks details over 20,000 characters and keeps the text', async () => {
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    type('Title', 'T');
    type('Details', 'b'.repeat(20001));
    expect(screen.getByRole('alert')).toHaveTextContent('1 characters over the 20,000-character limit');
    expect(screen.getByRole('button', { name: 'File request' })).toBeDisabled();
    expect(screen.getByLabelText('Details').value).toHaveLength(20001);
    type('Details', 'b'.repeat(20000));
    send();
    await waitFor(() => expect(submitIntake).toHaveBeenCalledTimes(1));
  });

  it('a double click files once', async () => {
    let resolve;
    submitIntake.mockReturnValue(new Promise((r) => { resolve = r; }));
    setup();
    await screen.findByRole('option', { name: 'Platform' });
    type('Title', 'Once');
    const btn = screen.getByRole('button', { name: 'File request' });
    fireEvent.click(btn); fireEvent.click(btn);
    expect(submitIntake).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Filing…' })).toBeDisabled());
    resolve({ key: 'PLAT-9' });
    expect(await screen.findByText('ITEM PAGE')).toBeInTheDocument();
  });

  it('a failed project load offers a retry', async () => {
    listReportable.mockRejectedValueOnce(Object.assign(new Error('down'), { status: 500 }));
    setup();
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('option', { name: 'Platform' })).toBeInTheDocument();
  });
});
