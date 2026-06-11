import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import MathValidationSummary from './MathValidationSummary';

const ERROR_VIOLATION = {
  locus: 'math',
  severity: 'ERROR',
  code: 'UNCLOSED_BRACE',
  message: 'Unclosed brace in LaTeX expression',
  location: {
    line: 5,
    column: 3,
    endLine: 5,
    endColumn: 10,
    startOffset: 42,
    endOffset: 49,
    excerpt: '\\frac{a}{b',
    caret: '         ^',
  },
};

const WARNING_VIOLATION = {
  locus: 'math',
  severity: 'WARNING',
  code: 'DEPRECATED_MACRO',
  message: 'Deprecated macro \\over',
  location: {
    line: 12,
    column: 1,
    endLine: 12,
    endColumn: 6,
    startOffset: 130,
    endOffset: 135,
    excerpt: '\\over',
    caret: '^^^^^',
  },
};

describe('MathValidationSummary', () => {
  it('renders nothing when violations is empty', () => {
    const { container } = render(<MathValidationSummary violations={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when violations is omitted', () => {
    const { container } = render(<MathValidationSummary />);
    expect(container.firstChild).toBeNull();
  });

  it('renders the violation message', () => {
    render(<MathValidationSummary violations={[ERROR_VIOLATION]} />);
    expect(screen.getByText('Unclosed brace in LaTeX expression')).toBeInTheDocument();
  });

  it('renders excerpt and caret in a <pre> element', () => {
    const { container } = render(<MathValidationSummary violations={[ERROR_VIOLATION]} />);
    const pre = container.querySelector('pre.math-violation-pinpoint');
    expect(pre).not.toBeNull();
    expect(pre.textContent).toContain('\\frac{a}{b');
    expect(pre.textContent).toContain('         ^');
  });

  it('clicking Jump calls onJump with the violation location', () => {
    const onJump = vi.fn();
    render(<MathValidationSummary violations={[ERROR_VIOLATION]} onJump={onJump} />);
    fireEvent.click(screen.getByRole('button', { name: /jump/i }));
    expect(onJump).toHaveBeenCalledWith(ERROR_VIOLATION.location);
  });

  it('ERROR violation gets a danger badge', () => {
    const { container } = render(<MathValidationSummary violations={[ERROR_VIOLATION]} />);
    expect(container.querySelector('.badge-danger')).not.toBeNull();
  });

  it('WARNING violation gets a warning badge (not danger)', () => {
    const { container } = render(<MathValidationSummary violations={[WARNING_VIOLATION]} />);
    expect(container.querySelector('.badge-warning')).not.toBeNull();
    expect(container.querySelector('.badge-danger')).toBeNull();
  });

  it('renders multiple violations', () => {
    const onJump = vi.fn();
    render(<MathValidationSummary violations={[ERROR_VIOLATION, WARNING_VIOLATION]} onJump={onJump} />);
    expect(screen.getByText('Unclosed brace in LaTeX expression')).toBeInTheDocument();
    expect(screen.getByText('Deprecated macro \\over')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /jump/i })).toHaveLength(2);
  });

  it('does not render a Jump button when onJump is not provided', () => {
    render(<MathValidationSummary violations={[ERROR_VIOLATION]} />);
    expect(screen.queryByRole('button', { name: /jump/i })).toBeNull();
  });
});
