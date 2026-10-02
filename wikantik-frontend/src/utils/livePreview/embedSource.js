import { loadEmbed } from '../embedCache';

/**
 * The shared embed cache (also used by the preview pane's WikiEmbed), normalised to the four states the
 * live-preview EmbedWidget renders. Ruling: embed adapter maps {html, missing, restricted} and HTTP
 * 403/404 rejections — matches WikiEmbed.
 */
export async function loadEmbedState(target, section) {
  try {
    const res = await loadEmbed(target, section);
    if (res?.restricted) return { state: 'restricted' };
    if (res?.missing) return { state: 'missing' };
    return { state: 'ok', html: res?.html ?? '' };
  } catch (err) {
    if (err?.status === 404) return { state: 'missing' };
    if (err?.status === 403) return { state: 'restricted' };
    console.warn('[live-preview] embed fetch failed', target, section, err?.message || err);
    return { state: 'error' };
  }
}
