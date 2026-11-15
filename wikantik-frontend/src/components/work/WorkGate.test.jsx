import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';

vi.mock('../../hooks/useCapabilities', () => ({ useCapabilities: vi.fn() }));
import { useCapabilities } from '../../hooks/useCapabilities';
import WorkGate from './WorkGate';

const renderGate = () => render(
  <MemoryRouter initialEntries={['/work']}>
    <Routes><Route path="/work" element={<WorkGate />}><Route index element={<div>inside</div>} /></Route></Routes>
  </MemoryRouter>);

describe('WorkGate', () => {
  it('renders the routes when work management is on', () => {
    useCapabilities.mockReturnValue({ capabilities: { work: true }, loading: false });
    renderGate();
    expect(screen.getByText('inside')).toBeInTheDocument();
  });

  it('says so plainly when the feature is off', () => {
    useCapabilities.mockReturnValue({ capabilities: { work: false }, loading: false });
    renderGate();
    expect(screen.getByText('Work management is not enabled')).toBeInTheDocument();
    expect(screen.queryByText('inside')).not.toBeInTheDocument();
  });

  it('waits for capabilities before deciding', () => {
    useCapabilities.mockReturnValue({ capabilities: {}, loading: true });
    renderGate();
    expect(screen.queryByText('Work management is not enabled')).not.toBeInTheDocument();
  });
});
