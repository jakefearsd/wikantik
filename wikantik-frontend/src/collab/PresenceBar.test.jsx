import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import PresenceBar from './PresenceBar.jsx';
import { labelOf } from './remoteCursors.js';

describe('PresenceBar', () => {
  it('theBarShowsDisplayNamesAndColors', () => {
    const self = { participantId: 'pt-1', displayName: 'Ada Lovelace', color: '#1b6ec2' };
    const presence = { 'pt-2': { displayName: 'Bob Stone', color: '#c2410c', anchor: null } };
    render(<PresenceBar self={self} presence={presence} status="live" />);
    expect(screen.getByText('Ada Lovelace')).toBeTruthy();
    expect(screen.getByText('Bob Stone')).toBeTruthy();
    expect(screen.getByText('AL')).toBeTruthy();
    expect(screen.getByText('BS').style.backgroundColor).toBeTruthy();
    expect(screen.getByText('2 editing')).toBeTruthy();
    expect(screen.getByText(/live/i)).toBeTruthy();
  });

  it('shows reconnecting state and no login-like fallback', () => {
    render(<PresenceBar self={{ participantId: 'a', displayName: 'Ada', color: '#111' }} presence={{}} status="reconnecting" />);
    expect(screen.getByText('1 editing')).toBeTruthy();
    expect(screen.getByText(/reconnecting/i)).toBeTruthy();
  });

  it('validates colours: a hostile value falls back to grey in the bar', () => {
    const presence = {
      a: { displayName: 'Eve', color: 'url(https://x/)' },
      b: { displayName: 'Mal', color: 'red;background:url(//x)' },
    };
    render(<PresenceBar self={{ participantId: 's', displayName: 'Ada', color: 'expression(1)' }} presence={presence} status="live" />);
    for (const t of ['AD', 'EV', 'MA']) expect(screen.getByText(t).style.backgroundColor).toBe('#6b7280');
    expect(screen.getByText('Eve').style.color).toBe('#6b7280');
  });

  it('uses the shared placeholder for a missing display name, never a login', () => {
    render(<PresenceBar self={{ participantId: 's', displayName: '', color: '#111111' }} presence={{ a: { displayName: null, color: '#222222' } }} status="live" />);
    expect(screen.getAllByText(labelOf(null))).toHaveLength(2);
  });
});
