export function fillTemplate(text, { title, date }) {
  return text.replaceAll('{{title}}', title).replaceAll('{{date}}', date);
}

export function buildInitialPage({ template, title, type, cluster, today }) {
  const initialMetadata = structuredClone(template?.metadata ?? { type, status: 'active' });
  initialMetadata.type = type;
  initialMetadata.date = today;
  if (cluster && cluster.trim()) initialMetadata.cluster = cluster.trim();
  const initialContent = fillTemplate(template?.body ?? '# {{title}}\n\n', { title, date: today });
  return { initialMetadata, initialContent };
}
