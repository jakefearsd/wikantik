import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';

vi.mock('../components/NewArticleModal', () => ({
  default: ({ initialTitle, onClose }) => (
    <div data-testid="modal">title:{initialTitle}<button onClick={onClose}>close</button></div>
  ),
}));

import { NewPageProvider, useNewPage } from './NewPageProvider';
import { getCommands, __resetRegistryForTest } from '../commands/registry';
import { runCommand } from '../commands/registry';

function Consumer() {
  const { openNewPage } = useNewPage();
  return <button onClick={() => openNewPage('From Overlay')}>open</button>;
}

describe('NewPageProvider', () => {
  beforeEach(() => __resetRegistryForTest());

  it('opens the modal with the title it was asked to prefill, and closes it', () => {
    render(<NewPageProvider><Consumer /></NewPageProvider>);
    expect(screen.queryByTestId('modal')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('open'));
    expect(screen.getByTestId('modal')).toHaveTextContent('title:From Overlay');
    fireEvent.click(screen.getByText('close'));
    expect(screen.queryByTestId('modal')).not.toBeInTheDocument();
  });

  it('registers the new-page command while mounted; running it opens the modal empty', async () => {
    const { unmount } = render(<NewPageProvider><Consumer /></NewPageProvider>);
    expect(getCommands().map((c) => c.id)).toContain('new-page');
    await act(async () => { await runCommand('new-page', { onError: vi.fn() }); });
    expect(screen.getByTestId('modal').textContent).toBe('title:close');
    unmount();
    expect(getCommands().map((c) => c.id)).not.toContain('new-page');
  });
});
