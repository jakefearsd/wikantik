import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, Link, useLocation } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import { NavigationGuardProvider, useNavigationGuard, useGuardedNavigate } from './NavigationGuardProvider';

function Where() { return <div data-testid="where">{useLocation().pathname}</div>; }

function Dirty({ dirty }) {
  useNavigationGuard(dirty);
  const go = useGuardedNavigate();
  return (
    <>
      <Link to="/wiki/B" data-testid="link">B</Link>
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
});
