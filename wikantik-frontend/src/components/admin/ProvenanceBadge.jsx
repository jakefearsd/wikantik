// Lifecycle of a graph entity / edge's provenance:
//   human-authored → value came directly from page frontmatter (most trusted)
//   ai-inferred    → LLM extractor proposed it; no human or judge has reviewed it
//   ai-reviewed    → the LLM judge approved it; still awaiting human curation
//   human-curated  → an admin explicitly confirmed it (least likely to be wrong)
// The badge renders the bare enum value with a tooltip explaining what it means,
// so admins triaging proposals don't need to memorise the lifecycle.
const PROVENANCE = {
  'human-authored': {
    bg: '#e8f5e9', color: '#2e7d32',
    tip: 'Value came directly from page frontmatter — authored by a human.',
  },
  'human-curated': {
    bg: '#c8e6c9', color: '#1b5e20',
    tip: 'An admin explicitly confirmed this entry — the highest curated tier.',
  },
  'ai-reviewed': {
    bg: '#e3f2fd', color: '#1565c0',
    tip: 'The LLM judge approved the proposal; still awaiting human curation.',
  },
  'ai-inferred': {
    bg: '#fff3e0', color: '#e65100',
    tip: "LLM extractor proposed it. Hasn't been reviewed by the judge or a human.",
  },
};

export default function ProvenanceBadge({ value }) {
  const meta = PROVENANCE[value];
  const style = meta || { bg: '#f5f5f5', color: '#616161', tip: null };
  return (
    <span
      title={meta ? meta.tip : undefined}
      style={{
        display: 'inline-block', padding: '2px 8px', borderRadius: '12px',
        fontSize: '0.8em', fontWeight: 500, backgroundColor: style.bg, color: style.color,
      }}
    >
      {value}
    </span>
  );
}
