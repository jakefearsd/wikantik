import { render, act, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { NavigationGuardProvider } from '../navigation/NavigationGuardProvider';
import { __resetRegistryForTest, getCommands, runCommand } from './registry';
import { useGlobalCommands } from './useGlobalCommands';

function Probe() { return <div data-testid="loc">{useLocation().pathname}</div>; }
function Host(props) { useGlobalCommands(props); return null; }

function mount(path, props) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <NavigationGuardProvider>
        <Host {...props} />
        <Probe />
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
}
const ids = () => getCommands().map((c) => c.id);

describe('useGlobalCommands', () => {
  beforeEach(() => { __resetRegistryForTest(); });

  it('registers the global commands on a wiki page', () => {
    mount('/wiki/Alpha', { openOverlay: vi.fn(), toggleSidebar: vi.fn() });
    expect(ids()).toEqual(expect.arrayContaining(
      ['go-to-page', 'search-full-text', 'edit-page', 'page-history', 'recent-changes', 'toggle-sidebar']));
  });

  it('go-to-page opens the overlay in pages mode', async () => {
    const openOverlay = vi.fn();
    mount('/wiki/Alpha', { openOverlay, toggleSidebar: vi.fn() });
    await act(async () => { await runCommand('go-to-page'); });
    expect(openOverlay).toHaveBeenCalledWith('pages');
  });

  it('toggle-sidebar calls the callback', async () => {
    const toggleSidebar = vi.fn();
    mount('/wiki/Alpha', { openOverlay: vi.fn(), toggleSidebar });
    await act(async () => { await runCommand('toggle-sidebar'); });
    expect(toggleSidebar).toHaveBeenCalled();
  });

  it('edit-page navigates to the editor', async () => {
    mount('/wiki/Alpha', { openOverlay: vi.fn(), toggleSidebar: vi.fn() });
    await act(async () => { await runCommand('edit-page'); });
    expect(screen.getByTestId('loc')).toHaveTextContent('/edit/Alpha');
  });

  it('page-history navigates to the diff route', async () => {
    mount('/wiki/Alpha', { openOverlay: vi.fn(), toggleSidebar: vi.fn() });
    await act(async () => { await runCommand('page-history'); });
    expect(screen.getByTestId('loc')).toHaveTextContent('/diff/Alpha');
  });

  it('edit-page is absent off a wiki page; page-history is present while editing', () => {
    mount('/search', { openOverlay: vi.fn(), toggleSidebar: vi.fn() });
    expect(ids()).not.toContain('edit-page');
    expect(ids()).not.toContain('page-history');
    __resetRegistryForTest();
    mount('/edit/Beta', { openOverlay: vi.fn(), toggleSidebar: vi.fn() });
    expect(ids()).not.toContain('edit-page');
    expect(ids()).toContain('page-history');
  });
});
