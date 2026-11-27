import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import WorkTokenPanel from './WorkTokenPanel';

const TOKEN = 'wka_x';
const ENDPOINT = 'https://h/work-mcp';
const COMMAND = 'claude mcp add --transport http wikantik-work https://h/work-mcp --header "Authorization: Bearer wka_x"';

describe('WorkTokenPanel', () => {
  let writeText;
  let spies;
  beforeEach(() => {
    writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    spies = ['log', 'info', 'warn', 'error'].map((m) => vi.spyOn(console, m).mockImplementation(() => {}));
  });
  afterEach(() => vi.restoreAllMocks());

  it('shows the endpoint and a claude mcp add command holding the token', () => {
    render(<WorkTokenPanel agentName="bot" token={TOKEN} endpoint={ENDPOINT} onDone={() => {}} />);
    expect(screen.getByText(ENDPOINT)).toBeInTheDocument();
    expect(screen.getByText(COMMAND)).toBeInTheDocument();
  });

  it('shows only the token without an endpoint', () => {
    render(<WorkTokenPanel agentName="bot" token={TOKEN} onDone={() => {}} />);
    expect(screen.getByText(TOKEN)).toBeInTheDocument();
    expect(screen.queryByText(/claude mcp add/)).toBeNull();
    expect(screen.getAllByRole('button', { name: /copy/i })).toHaveLength(1);
  });

  it('copies token, URL and command only on click, and logs nothing', async () => {
    render(<WorkTokenPanel agentName="bot" token={TOKEN} endpoint={ENDPOINT} onDone={() => {}} />);
    expect(writeText).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(TOKEN));
    fireEvent.click(screen.getByRole('button', { name: 'Copy URL' }));
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(ENDPOINT));
    fireEvent.click(screen.getByRole('button', { name: 'Copy command' }));
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(COMMAND));
    expect(await screen.findByText(/Copied/)).toBeInTheDocument();
    for (const s of spies) {
      for (const call of s.mock.calls) expect(JSON.stringify(call)).not.toContain(TOKEN);
    }
  });
});
