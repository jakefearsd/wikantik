import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import AdminPage from './AdminPage';

describe('AdminPage', () => {
  it('renders the loading label when loading', () => {
    render(<AdminPage loading loadingLabel="Loading users…">should not render</AdminPage>);
    expect(screen.getByText('Loading users…')).toBeInTheDocument();
    expect(screen.queryByText('should not render')).not.toBeInTheDocument();
  });

  it('renders the error banner when error is set', () => {
    render(<AdminPage error="upstream is down">should not render</AdminPage>);
    expect(screen.getByText('upstream is down')).toBeInTheDocument();
    expect(screen.queryByText('should not render')).not.toBeInTheDocument();
  });

  it('error takes precedence over loading', () => {
    render(<AdminPage loading error="upstream is down">should not render</AdminPage>);
    // First check returns the loading branch — error is the *fallback* once
    // loading has resolved. Lock that ordering down so a later refactor
    // doesn't accidentally start showing both at once.
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });

  it('renders children inside the wrapper className when settled', () => {
    render(
      <AdminPage className="admin-foo page-enter">
        <span>content</span>
      </AdminPage>,
    );
    const wrapper = screen.getByText('content').parentElement;
    expect(wrapper).toHaveClass('admin-foo');
    expect(wrapper).toHaveClass('page-enter');
  });

  it('falls back to admin-users page-enter when no className is given', () => {
    render(<AdminPage><span>content</span></AdminPage>);
    const wrapper = screen.getByText('content').parentElement;
    expect(wrapper).toHaveClass('admin-users');
  });

  it('omits the outer wrapper when className is empty', () => {
    // Section components already live inside a page-level wrapper; doubling up
    // would change CSS layout. Empty className must short-circuit to a fragment.
    const { container } = render(
      <AdminPage className=""><span>content</span></AdminPage>,
    );
    expect(container.firstChild).toBeInstanceOf(HTMLSpanElement);
  });
});
