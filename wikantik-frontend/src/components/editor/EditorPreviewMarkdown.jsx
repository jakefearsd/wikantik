import { memo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkHighlight from '../../utils/remarkHighlight';

/**
 * The editor preview's markdown render. react-markdown has no memoization of its own: every render re-parses
 * and re-transforms the whole page. Memoized here (with stable plugin arrays from the caller) so only a change
 * of the text, the plugins' inputs or the components re-runs it — not every PageEditor render. Each run's
 * cost is written to `costMeter` (usePreviewSource reads it to decide between live and settled previews).
 *
 * The Obsidian syntax the reader renders is added here, after the caller's stack, so every preview has it:
 * `==highlight==` (remarkHighlight).
 */
function EditorPreviewMarkdown({ content, remarkPlugins, rehypePlugins, components, costMeter }) {
  const start = performance.now();
  // Called directly (it is a hook-free function component) so the timing covers the parse/transform pipeline.
  const tree = ReactMarkdown({ children: content, components, remarkPlugins: [...(remarkPlugins || []), remarkHighlight], rehypePlugins });
  if (costMeter) costMeter.ms = performance.now() - start;
  return tree;
}

export default memo(EditorPreviewMarkdown);
