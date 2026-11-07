import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import PresenceBar from './PresenceBar.jsx';

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
});
