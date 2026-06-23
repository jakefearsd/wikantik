// AuditRecordModal.jsx
// Read-only detail view of a single audit record, shown when a row is clicked.
// Renders every field the REST API returns; parses the `detail` JSON defensively.
import Modal from '../ui/Modal';

function parseDetail(detail) {
  if (!detail) return null;
  try {
    return JSON.parse(detail);
  } catch {
    return { _raw: detail };
  }
}

function Row({ label, value }) {
  if (value === null || value === undefined || value === '') return null;
  return (
    <div className="audit-detail-row" style={{ display: 'flex', gap: 'var(--space-md)', padding: '2px 0' }}>
      <span style={{ minWidth: '140px', color: 'var(--color-text-muted)' }}>{label}</span>
      <span style={{ wordBreak: 'break-word' }}>{String(value)}</span>
    </div>
  );
}

function Section({ title, children }) {
  return (
    <section style={{ marginBottom: 'var(--space-md)' }}>
      <h3 style={{ fontSize: '0.85rem', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--color-text-muted)' }}>{title}</h3>
      {children}
    </section>
  );
}

export default function AuditRecordModal({ record, onClose }) {
  if (!record) return null;
  const detail = parseDetail(record.detail);

  return (
    <Modal isOpen onClose={onClose} labelledBy="audit-record-title" testId="audit-record-modal">
      <div style={{ maxWidth: '640px' }}>
        <h2 id="audit-record-title" style={{ marginTop: 0 }}>
          Audit record #{record.seq}
        </h2>

        <Section title="Event">
          <Row label="Time" value={record.eventTime} />
          <Row label="Category" value={record.category} />
          <Row label="Event" value={record.eventType} />
          <Row label="Outcome" value={record.outcome} />
        </Section>

        <Section title="Actor">
          <Row label="Principal" value={record.actorPrincipal} />
          <Row label="Type" value={record.actorType} />
          <Row label="Id" value={record.actorId} />
        </Section>

        <Section title="Target">
          <Row label="Type" value={record.targetType} />
          <Row label="Id" value={record.targetId} />
          <Row label="Label" value={record.targetLabel} />
        </Section>

        <Section title="Request / why">
          {detail && detail._raw === undefined && (
            <>
              <Row label="Permission" value={detail.permission} />
              <Row label="Method" value={detail.method} />
              <Row label="URI" value={detail.uri} />
              <Row label="Reason" value={detail.reason} />
              <Row label="Auth status" value={detail.authStatus} />
              <Row label="Roles" value={detail.roles} />
            </>
          )}
          {detail && detail._raw !== undefined && <Row label="Detail" value={detail._raw} />}
          <Row label="Source IP" value={record.sourceIp} />
          <Row label="User agent" value={record.userAgent} />
          <Row label="Correlation id" value={record.correlationId} />
        </Section>

        <Section title="Integrity">
          <Row label="Row hash" value={record.rowHash} />
          <Row label="Prev hash" value={record.prevHash} />
        </Section>

        <div style={{ textAlign: 'right', marginTop: 'var(--space-md)' }}>
          <button type="button" className="btn btn-ghost" onClick={onClose}>Close</button>
        </div>
      </div>
    </Modal>
  );
}
