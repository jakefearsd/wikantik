import { livePreviewConfig, liveModeField, setLiveMode, blockField, livePreviewPlugin, liveAttributes } from './plugin';
import { referenceDefsField } from './ranges';
import { verticalMotionKeymap } from './verticalMotion';
import { stickyExtension } from './sticky';
import { followReadOnly } from './headers';
import { obsidianSyntaxKeymap } from '../obsidianSyntaxKeymap';
import { linkCardExtension, onOpenLinkCard } from './linkCard';

export { setLiveMode, refreshLivePreview, liveModeField } from './plugin';

/**
 * The live-preview extension. Inactive until setLivePreview(view, true). getContext() returns the LiveContext.
 * `onLinkCard(card | null, view)`, when given, shows the link card: without it a click on a chip places the caret.
 */
export function livePreview({ getContext = () => ({}), onLinkCard } = {}) {
  return [liveModeField, livePreviewConfig.of({ getContext }), referenceDefsField, blockField, livePreviewPlugin, liveAttributes, verticalMotionKeymap, stickyExtension, followReadOnly, obsidianSyntaxKeymap,
    linkCardExtension, ...(onLinkCard ? [onOpenLinkCard.of(onLinkCard)] : [])];
}

/** Turn live mode on/off through a view transaction (effects only: no document change, no history entry). */
export function setLivePreview(view, on) {
  if (view.state.field(liveModeField, false) === undefined) return;
  if (view.state.field(liveModeField) !== !!on) view.dispatch({ effects: setLiveMode.of(!!on) });
}
