import { livePreviewConfig, liveModeField, setLiveMode, blockField, livePreviewPlugin, liveAttributes } from './plugin';
import { referenceDefsField } from './ranges';
import { verticalMotionKeymap } from './verticalMotion';
import { obsidianSyntaxKeymap } from '../obsidianSyntaxKeymap';

export { setLiveMode, refreshLivePreview, liveModeField } from './plugin';

/** The live-preview extension. Inactive until setLivePreview(view, true). getContext() returns the LiveContext. */
export function livePreview({ getContext = () => ({}) } = {}) {
  return [liveModeField, livePreviewConfig.of({ getContext }), referenceDefsField, blockField, livePreviewPlugin, liveAttributes, verticalMotionKeymap, obsidianSyntaxKeymap];
}

/** Turn live mode on/off through a view transaction (effects only: no document change, no history entry). */
export function setLivePreview(view, on) {
  if (view.state.field(liveModeField, false) === undefined) return;
  if (view.state.field(liveModeField) !== !!on) view.dispatch({ effects: setLiveMode.of(!!on) });
}
