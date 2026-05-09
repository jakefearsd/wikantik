import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import Pagination from './Pagination';

describe('Pagination', () => {
  it('hides when totalCount is 0', () => {
    const { container } = render(
      <Pagination currentPage={0} pageSize={25} totalCount={0} onPageChange={vi.fn()} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('hides when totalCount is negative (defensive)', () => {
    const { container } = render(
      <Pagination currentPage={0} pageSize={25} totalCount={-1} onPageChange={vi.fn()} />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders prev/next + indicator + count when there are rows', () => {
    render(<Pagination currentPage={0} pageSize={25} totalCount={75} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Previous page/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Next page/i })).toBeInTheDocument();
    expect(screen.getByText(/Page 1 of 3/)).toBeInTheDocument();
    expect(screen.getByText(/1–25 of 75/)).toBeInTheDocument();
  });

  it('disables Prev on the first page', () => {
    render(<Pagination currentPage={0} pageSize={25} totalCount={100} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Previous page/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Next page/i })).not.toBeDisabled();
  });

  it('disables Next on the last page', () => {
    render(<Pagination currentPage={3} pageSize={25} totalCount={100} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Next page/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Previous page/i })).not.toBeDisabled();
  });

  it('disables both when total fits in one page', () => {
    render(<Pagination currentPage={0} pageSize={25} totalCount={10} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Previous page/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Next page/i })).toBeDisabled();
    expect(screen.getByText(/Page 1 of 1/)).toBeInTheDocument();
  });

  it('disables both when total equals exactly one full page', () => {
    render(<Pagination currentPage={0} pageSize={25} totalCount={25} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Next page/i })).toBeDisabled();
    expect(screen.getByText(/1–25 of 25/)).toBeInTheDocument();
  });

  it('renders correct partial-page ceiling for odd totals', () => {
    render(<Pagination currentPage={0} pageSize={25} totalCount={26} onPageChange={vi.fn()} />);
    expect(screen.getByText(/Page 1 of 2/)).toBeInTheDocument();
  });

  it('shows correct row range on the last partial page', () => {
    render(<Pagination currentPage={2} pageSize={25} totalCount={62} onPageChange={vi.fn()} />);
    expect(screen.getByText(/51–62 of 62/)).toBeInTheDocument();
  });

  it('clicking Prev fires onPageChange with currentPage-1', () => {
    const onPageChange = vi.fn();
    render(<Pagination currentPage={2} pageSize={25} totalCount={100} onPageChange={onPageChange} />);
    fireEvent.click(screen.getByRole('button', { name: /Previous page/i }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('clicking Next fires onPageChange with currentPage+1', () => {
    const onPageChange = vi.fn();
    render(<Pagination currentPage={1} pageSize={25} totalCount={100} onPageChange={onPageChange} />);
    fireEvent.click(screen.getByRole('button', { name: /Next page/i }));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  it('does not fire onPageChange when Prev is clicked while disabled', () => {
    const onPageChange = vi.fn();
    render(<Pagination currentPage={0} pageSize={25} totalCount={100} onPageChange={onPageChange} />);
    fireEvent.click(screen.getByRole('button', { name: /Previous page/i }));
    expect(onPageChange).not.toHaveBeenCalled();
  });

  it('clamps an out-of-range currentPage so display is sane', () => {
    // currentPage=99 with totalCount=100 (4 pages) — should clamp to last valid page (3).
    render(<Pagination currentPage={99} pageSize={25} totalCount={100} onPageChange={vi.fn()} />);
    expect(screen.getByText(/Page 4 of 4/)).toBeInTheDocument();
    expect(screen.getByText(/76–100 of 100/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Next page/i })).toBeDisabled();
  });

  it('formats large totals with thousand separators', () => {
    render(<Pagination currentPage={5} pageSize={25} totalCount={12345} onPageChange={vi.fn()} />);
    expect(screen.getByText(/of 12,345/)).toBeInTheDocument();
  });
});
