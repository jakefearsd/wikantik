import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkProjectContext, useWorkProject } from './useWorkProject';

function Probe() {
  const { project, role, reload } = useWorkProject();
  return <div data-testid="p">{String(project?.key)}|{String(role)}|{typeof reload}</div>;
}

describe('useWorkProject', () => {
  it('gives the project, role and reload the layout provided', () => {
    const reload = vi.fn();
    render(<WorkProjectContext.Provider value={{ project: { key: 'PLAT' }, role: 'lead', reload }}><Probe /></WorkProjectContext.Provider>);
    expect(screen.getByTestId('p')).toHaveTextContent('PLAT|lead|function');
  });

  it('gives an empty project, no role and a harmless reload outside a layout', () => {
    function Calls() {
      const { reload } = useWorkProject();
      return <button type="button" onClick={() => reload()}>go</button>;
    }
    render(<><Probe /><Calls /></>);
    expect(screen.getByTestId('p')).toHaveTextContent('undefined|null|function');
    expect(() => screen.getByRole('button', { name: 'go' }).click()).not.toThrow();
  });
});
