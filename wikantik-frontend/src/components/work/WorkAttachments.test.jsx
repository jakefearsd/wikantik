import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('../../api/work', () => ({
  listAttachments: vi.fn(), uploadAttachment: vi.fn(), removeAttachment: vi.fn(),
  attachmentUrl: (k, id) => `/a/${k}/${id}`,
}));
import { listAttachments, uploadAttachment, removeAttachment } from '../../api/work';
import { resetLimits } from './attachmentErrors';
import WorkAttachments from './WorkAttachments';

const att = (id, extra = {}) => ({
  id, filename: `f${id}.pdf`, contentType: 'application/pdf', sizeBytes: 2048, uploadedByName: 'Ana Alvarez',
  uploadedByKind: 'human', mine: false, uploadedAt: '2026-11-01T10:00:00Z', url: `/api/work/items/PLAT-1/attachments/${id}`,
  inline: false, markdown: `[f${id}.pdf](work-attachment:${id})`, ...extra,
});
const png = (id) => att(id, { filename: `s${id}.png`, contentType: 'image/png', inline: true });
const MB = 1024 * 1024;
const L = (attachments, extra = {}) => ({
  attachments, mayAttach: true, maxFileBytes: 10 * MB, maxItemBytes: 50 * MB, usedBytes: 0, ...extra,
});
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
const setup = (props = {}) => render(<WorkAttachments itemKey="PLAT-1" canAttach isLead={false} toast={toast} {...props} />);

beforeEach(() => {
  vi.clearAllMocks(); resetLimits();
  listAttachments.mockResolvedValue(L([]));
});

describe('WorkAttachments', () => {
  it('listsNameSizeAndUploaderWithTheDownloadLink', async () => {
    listAttachments.mockResolvedValue(L([att(1)]));
    setup();
    const link = await screen.findByRole('link', { name: 'f1.pdf' });
    expect(link).toHaveAttribute('href', '/a/PLAT-1/1');
    expect(link).toHaveAttribute('download');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(screen.getByText('2.0 KB')).toBeInTheDocument();
    expect(screen.getByText('Ana Alvarez')).toBeInTheDocument();
    expect(document.querySelector('img, iframe, object')).toBeNull();
  });

  it('showsAThumbnailOnlyForAnInlineImage', async () => {
    listAttachments.mockResolvedValue(L([png(2), att(3, { filename: 'x.svg', contentType: 'image/svg+xml', inline: true })]));
    setup();
    await screen.findByRole('link', { name: 's2.png' });
    const imgs = document.querySelectorAll('img');
    expect(imgs).toHaveLength(1);
    expect(imgs[0]).toHaveAttribute('src', '/a/PLAT-1/2');
    expect(screen.getByRole('link', { name: 'x.svg' })).toHaveAttribute('download');
  });

  it('rendersAFileNameAsTextNeverHtml', async () => {
    listAttachments.mockResolvedValue(L([att(1, { filename: '<img src=x onerror=alert(1)>.pdf' })]));
    setup();
    await screen.findByText('<img src=x onerror=alert(1)>.pdf');
    expect(document.querySelector('img')).toBeNull();
  });

  it('offersRemoveToTheUploaderAndToALeadOnly', async () => {
    listAttachments.mockResolvedValue(L([att(1, { mine: true }), att(2)]));
    const { unmount } = setup();
    await screen.findByRole('link', { name: 'f1.pdf' });
    expect(screen.getAllByRole('button', { name: /^Remove / })).toHaveLength(1);
    unmount();
    setup({ isLead: true });
    await screen.findAllByRole('link', { name: 'f1.pdf' });
    expect(screen.getAllByRole('button', { name: /^Remove / })).toHaveLength(2);
  });

  it('aViewerGetsNoControls', async () => {
    listAttachments.mockResolvedValue(L([att(1, { mine: true })]));
    setup({ canAttach: false, isLead: true });
    await screen.findByRole('link', { name: 'f1.pdf' });
    expect(screen.queryByRole('button', { name: /^Remove / })).toBeNull();
    expect(screen.queryByLabelText('Attach a file')).toBeNull();
  });

  it('removesOnlyAfterConfirmingAndTheServerAnswers', async () => {
    listAttachments.mockResolvedValue(L([att(1, { mine: true })]));
    let finish;
    removeAttachment.mockReturnValue(new Promise((r) => { finish = r; }));
    const onChanged = vi.fn();
    setup({ onChanged });
    fireEvent.click(await screen.findByRole('button', { name: 'Remove f1.pdf' }));
    expect(removeAttachment).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove' }));
    expect(removeAttachment).toHaveBeenCalledWith('PLAT-1', 1);
    expect(screen.getByRole('link', { name: 'f1.pdf' })).toBeInTheDocument();
    finish(null);
    await waitFor(() => expect(screen.queryByRole('link', { name: 'f1.pdf' })).toBeNull());
    expect(onChanged).toHaveBeenCalled();
  });

  it('aFailedRemoveShowsAnAlertAndKeepsTheRow', async () => {
    listAttachments.mockResolvedValue(L([att(1, { mine: true })]));
    removeAttachment.mockRejectedValue(Object.assign(new Error('Not allowed'), { status: 403 }));
    setup();
    fireEvent.click(await screen.findByRole('button', { name: 'Remove f1.pdf' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/not allowed/i);
    expect(screen.getByRole('link', { name: 'f1.pdf' })).toBeInTheDocument();
  });

  it('theFileInputUploadsEachFileInTurnAndReloads', async () => {
    uploadAttachment.mockResolvedValue(att(9));
    const onChanged = vi.fn();
    setup({ onChanged });
    await waitFor(() => expect(listAttachments).toHaveBeenCalledTimes(1));
    const a = new File(['a'], 'a.txt', { type: 'text/plain' });
    const b = new File(['b'], 'b.txt', { type: 'text/plain' });
    fireEvent.change(screen.getByLabelText('Attach a file'), { target: { files: [a, b] } });
    await waitFor(() => expect(uploadAttachment).toHaveBeenCalledTimes(2));
    expect(uploadAttachment.mock.calls.map((c) => c[1].name)).toEqual(['a.txt', 'b.txt']);
    await waitFor(() => expect(listAttachments.mock.calls.length).toBeGreaterThan(1));
    expect(onChanged).toHaveBeenCalled();
  });

  it('reportsEachFailureInWordsAndStillSendsTheRest', async () => {
    uploadAttachment
      .mockRejectedValueOnce(Object.assign(new Error('x'), { status: 413, body: { reason: 'too_large', message: 'any words at all' } }))
      .mockResolvedValueOnce(att(9));
    setup();
    await waitFor(() => expect(listAttachments).toHaveBeenCalled());
    const a = new File(['a'], 'a.txt'); const b = new File(['b'], 'b.txt');
    fireEvent.change(screen.getByLabelText('Attach a file'), { target: { files: [a, b] } });
    const alerts = await screen.findAllByRole('alert');
    expect(alerts[0]).toHaveTextContent('a.txt: too large; one file may be at most 10.0 MB.');
    await waitFor(() => expect(uploadAttachment).toHaveBeenCalledTimes(2));
  });

  it('refusesAnOversizeFileWithoutSendingIt', async () => {
    setup();
    await waitFor(() => expect(listAttachments).toHaveBeenCalled());
    const big = new File([new Uint8Array(11 * MB)], 'big.bin');
    fireEvent.change(screen.getByLabelText('Attach a file'), { target: { files: [big] } });
    expect(await screen.findByRole('alert')).toHaveTextContent(/big\.bin is larger than 10\.0 MB/);
    expect(uploadAttachment).not.toHaveBeenCalled();
  });

  it('theClientCheckUsesTheServersFileLimit', async () => {
    listAttachments.mockResolvedValue(L([], { maxFileBytes: 20 * MB }));
    uploadAttachment.mockResolvedValue(att(9));
    setup();
    await waitFor(() => expect(listAttachments).toHaveBeenCalled());
    const file = new File([new Uint8Array(12 * MB)], 'twelve.bin');
    await screen.findByLabelText('Attach a file');
    await waitFor(() => expect(listAttachments).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText('Attach a file'), { target: { files: [file] } });
    await waitFor(() => expect(uploadAttachment).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('aFileThatWouldOverflowTheItemIsRefusedWithTheItemFullWording', async () => {
    listAttachments.mockResolvedValue(L([att(1)], { maxFileBytes: 20 * MB, maxItemBytes: 30 * MB, usedBytes: 25 * MB }));
    setup();
    await screen.findByRole('link', { name: 'f1.pdf' });
    const file = new File([new Uint8Array(6 * MB)], 'six.bin');
    fireEvent.change(screen.getByLabelText('Attach a file'), { target: { files: [file] } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('six.bin does not fit: this item may hold 30.0 MB of files and 25.0 MB is used, leaving 5.0 MB.');
    expect(uploadAttachment).not.toHaveBeenCalled();
  });

  it('beforeTheListLoadsNothingIsPreCheckedTheServerAnswers', async () => {
    listAttachments.mockReturnValue(new Promise(() => {}));
    uploadAttachment.mockResolvedValue(att(9));
    setup();
    const file = new File([new Uint8Array(11 * MB)], 'early.bin');
    fireEvent.change(screen.getByLabelText('Attach a file'), { target: { files: [file] } });
    await waitFor(() => expect(uploadAttachment).toHaveBeenCalledTimes(1));
  });

  it('theAttachControlIsHiddenWhenTheServerSaysTheCallerMayNotAttach', async () => {
    listAttachments.mockResolvedValue(L([att(1)], { mayAttach: false }));
    setup();
    await screen.findByRole('link', { name: 'f1.pdf' });
    expect(screen.queryByLabelText('Attach a file')).toBeNull();
  });

  it('theItemFullReasonGetsItsOwnWords', async () => {
    listAttachments.mockResolvedValue(L([], { maxItemBytes: 30 * MB, usedBytes: 25 * MB }));
    uploadAttachment.mockRejectedValue(Object.assign(new Error('x'), { status: 413, body: { reason: 'item_full', message: 'ignored' } }));
    setup();
    await waitFor(() => expect(listAttachments).toHaveBeenCalled());
    await waitFor(() => expect(listAttachments).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 0));
    fireEvent.change(screen.getByLabelText('Attach a file'), { target: { files: [new File(['a'], 'a.txt')] } });
    expect(await screen.findByRole('alert')).toHaveTextContent('a.txt: this item is full; its files may total at most 30.0 MB.');
  });

  it('aFailedLoadShowsRetryAndAFailedReloadKeepsTheList', async () => {
    listAttachments.mockRejectedValueOnce(new Error('down'));
    const { rerender } = setup();
    expect(await screen.findByText(/Could not load the attachments/)).toBeInTheDocument();
    listAttachments.mockResolvedValueOnce(L([att(1)]));
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByRole('link', { name: 'f1.pdf' });
    listAttachments.mockRejectedValueOnce(new Error('down'));
    rerender(<WorkAttachments itemKey="PLAT-1" canAttach isLead={false} toast={toast} refreshKey={1} />);
    await screen.findByText(/Could not load the attachments/);
    expect(screen.getByRole('link', { name: 'f1.pdf' })).toBeInTheDocument();
  });

  it('theActivityDrivenRefreshIsSilent', async () => {
    const { rerender } = setup();
    await waitFor(() => expect(listAttachments).toHaveBeenCalledTimes(1));
    expect(listAttachments).toHaveBeenLastCalledWith('PLAT-1', undefined);
    rerender(<WorkAttachments itemKey="PLAT-1" canAttach isLead={false} toast={toast} refreshKey={1} />);
    await waitFor(() => expect(listAttachments).toHaveBeenCalledTimes(2));
    expect(listAttachments).toHaveBeenLastCalledWith('PLAT-1', { silent: true });
  });
});
