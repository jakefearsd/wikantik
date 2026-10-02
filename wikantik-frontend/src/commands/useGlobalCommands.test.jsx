import { render, act, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { NavigationGuardProvider } from '../navigation/NavigationGuardProvider';
import { __resetRegistryForTest, getCommands, runCommand } from './registry';
import { useGlobalCommands } from './useGlobalCommands';
import { api } from '../api/client';
import { useNavigationGuard } from '../navigation/NavigationGuardProvider';

vi.mock('../api/client', () => ({ api: { listPages: vi.fn(), listClusters: vi.fn() } }));

function Probe() { return <div data-testid="loc">{useLocation().pathname}</div>; }
function DirtyHost(props) { useGlobalCommands(props); useNavigationGuard(true); return null; }
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

  it("daily-note is registered everywhere and opens today's note", async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    api.listClusters.mockResolvedValue({ clusters: [] });
    mount('/search', { openOverlay: vi.fn(), toggleSidebar: vi.fn() });
    expect(getCommands().find((c) => c.id === 'daily-note')).toMatchObject({ section: 'Page', keys: 'Mod-Alt-N' });
    await act(async () => { await runCommand('daily-note'); });
    expect(screen.getByTestId('loc').textContent).toMatch(/^\/edit\/\d{4}-\d{2}-\d{2}$/);
  });

  it('daily-note asks first when the current page has unsaved changes', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'x' }] });
    render(<MemoryRouter initialEntries={['/edit/Alpha']}><NavigationGuardProvider>
      <DirtyHost openOverlay={vi.fn()} toggleSidebar={vi.fn()} /><Probe /></NavigationGuardProvider></MemoryRouter>);
    await act(async () => { await runCommand('daily-note'); });
    expect(screen.getByTestId('guard-dialog')).toBeInTheDocument();
    expect(screen.getByTestId('loc')).toHaveTextContent('/edit/Alpha');
  });
});
