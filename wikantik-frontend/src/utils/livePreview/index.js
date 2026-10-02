import { livePreviewConfig, liveModeField, setLiveMode, blockField, livePreviewPlugin, liveAttributes } from './plugin';

export { setLiveMode, refreshLivePreview, liveModeField } from './plugin';

/** The live-preview extension. Inactive until setLivePreview(view, true). getContext() returns the LiveContext. */
export function livePreview({ getContext = () => ({}) } = {}) {
  return [liveModeField, livePreviewConfig.of({ getContext }), blockField, livePreviewPlugin, liveAttributes];
}

/** Turn live mode on/off through a view transaction (effects only: no document change, no history entry). */
export function setLivePreview(view, on) {
  if (view.state.field(liveModeField, false) === undefined) return;
  if (view.state.field(liveModeField) !== !!on) view.dispatch({ effects: setLiveMode.of(!!on) });
}
