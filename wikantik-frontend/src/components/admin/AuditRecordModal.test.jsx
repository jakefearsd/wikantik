import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import AuditRecordModal from './AuditRecordModal';

const ROW = {
  seq: 42,
  eventTime: '2026-06-23T10:00:00Z',
  category: 'AUTHZ',
  eventType: 'access.denied',
  outcome: 'DENIED',
  actorPrincipal: 'alice',
  actorType: 'user',
  targetType: 'page',
  targetId: 'SecretPage',
  targetLabel: 'edit → SecretPage',
  sourceIp: '203.0.113.7',
  userAgent: 'curl/8.4.0',
  correlationId: 'req-xyz',
  rowHash: 'abc',
  prevHash: 'def',
  detail: '{"permission":"*:SecretPage","uri":"/api/pages/SecretPage","method":"POST","reason":"acl-denied","authStatus":"authenticated","roles":"Authenticated,All"}',
};

describe('AuditRecordModal', () => {
  it('renders the parsed detail fields and core record data', () => {
    render(<AuditRecordModal record={ROW} onClose={() => {}} />);
    expect(screen.getByText('acl-denied')).toBeInTheDocument();      // reason
    expect(screen.getByText('authenticated')).toBeInTheDocument();   // authStatus
    expect(screen.getByText('curl/8.4.0')).toBeInTheDocument();      // userAgent
    expect(screen.getByText('edit → SecretPage')).toBeInTheDocument(); // targetLabel
    expect(screen.getByText('POST')).toBeInTheDocument();            // method
  });

  it('calls onClose when the close button is clicked', () => {
    const onClose = vi.fn();
    render(<AuditRecordModal record={ROW} onClose={onClose} />);
    fireEvent.click(screen.getByRole('button', { name: /close/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders raw detail text when detail is not valid JSON', () => {
    render(<AuditRecordModal record={{ ...ROW, detail: 'not-json' }} onClose={() => {}} />);
    expect(screen.getByText('not-json')).toBeInTheDocument();
  });
});
