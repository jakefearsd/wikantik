import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, Link, useLocation } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { NavigationGuardProvider, useNavigationGuard, useGuardedNavigate } from './NavigationGuardProvider';

function Where() { return <div data-testid="where">{useLocation().pathname}</div>; }

function Dirty({ dirty }) {
  useNavigationGuard(dirty);
  const go = useGuardedNavigate();
  return (
    <>
      <Link to="/wiki/B" data-testid="link">B</Link>
      <a href="/attach/A/report.pdf" data-testid="attachment">report</a>
      <button type="button" onClick={() => go('/wiki/C')}>go</button>
    </>
  );
}

function setup(dirty) {
  return render(
    <MemoryRouter initialEntries={['/edit/A']}>
      <NavigationGuardProvider>
        <Routes><Route path="*" element={<><Dirty dirty={dirty} /><Where /></>} /></Routes>
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
}

describe('NavigationGuardProvider', () => {
  it('lets links through when nothing is dirty', () => {
    setup(false);
    fireEvent.click(screen.getByTestId('link'));
    expect(screen.getByTestId('where').textContent).toBe('/wiki/B');
  });

  it('holds a link click behind the dialog while dirty; Stay keeps you here', () => {
    setup(true);
    fireEvent.click(screen.getByTestId('link'));
    expect(screen.getByTestId('guard-dialog')).toHaveTextContent(
      "You have unsaved changes. Your draft is kept in this browser but isn't saved to the wiki.");
    expect(screen.getByTestId('where').textContent).toBe('/edit/A');
    fireEvent.click(screen.getByTestId('guard-stay'));
    expect(screen.queryByTestId('guard-dialog')).toBeNull();
    expect(screen.getByTestId('where').textContent).toBe('/edit/A');
  });

  it('Leave without saving completes the navigation', () => {
    setup(true);
    fireEvent.click(screen.getByTestId('link'));
    fireEvent.click(screen.getByTestId('guard-leave'));
    expect(screen.getByTestId('where').textContent).toBe('/wiki/B');
  });

  it('guards programmatic navigation too', () => {
    setup(true);
    fireEvent.click(screen.getByText('go'));
    expect(screen.getByTestId('guard-dialog')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('guard-leave'));
    expect(screen.getByTestId('where').textContent).toBe('/wiki/C');
  });

  it('never intercepts a ctrl-click', () => {
    setup(true);
    fireEvent.click(screen.getByTestId('link'), { ctrlKey: true });
    expect(screen.queryByTestId('guard-dialog')).toBeNull();
  });

  describe('links the router does not serve', () => {
    // happy-dom performs an un-prevented link's default navigation (the ctrl-click test above moves
    // window.location to /wiki/B), so start each case from a neutral document URL.
    beforeEach(() => { window.history.replaceState(null, '', '/'); });
    afterEach(() => { vi.restoreAllMocks(); });

    it('Leave without saving loads a non-SPA same-origin link as a full page, not a router route', () => {
      const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {});
      setup(true);
      fireEvent.click(screen.getByTestId('attachment'));
      expect(screen.getByTestId('guard-dialog')).toBeInTheDocument();
      fireEvent.click(screen.getByTestId('guard-leave'));
      expect(assign).toHaveBeenCalledWith(new URL('/attach/A/report.pdf', window.location.href).href);
      expect(screen.getByTestId('where').textContent).toBe('/edit/A');
    });

    it('Stay on a non-SPA link loads nothing', () => {
      const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {});
      setup(true);
      fireEvent.click(screen.getByTestId('attachment'));
      fireEvent.click(screen.getByTestId('guard-stay'));
      expect(assign).not.toHaveBeenCalled();
    });

    it('SPA links still navigate in-app on Leave', () => {
      const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {});
      setup(true);
      fireEvent.click(screen.getByTestId('link'));
      fireEvent.click(screen.getByTestId('guard-leave'));
      expect(assign).not.toHaveBeenCalled();
      expect(screen.getByTestId('where').textContent).toBe('/wiki/B');
    });
  });
});
