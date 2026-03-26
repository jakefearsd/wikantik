/**
 * Converts a human-readable title to a CamelCase wiki page name/slug.
 * "AI Model Training in 2026" → "AIModelTrainingIn2026"
 * "retirement planning in spain" → "RetirementPlanningInSpain"
 * "Berlin: History & Culture" → "BerlinHistoryAndCulture"
 * "what is a 401(k)?" → "WhatIsA401k"
 */
export function titleToSlug(title) {
  return title
    .trim()
    .split(/[\s\-_]+/)
    .filter(Boolean)
    .map(word => {
      word = word.replace(/&/g, 'And');
      word = word.replace(/[^a-zA-Z0-9]/g, '');
      if (!word) return '';
      return word.charAt(0).toUpperCase() + word.slice(1);
    })
    .filter(Boolean)
    .join('');
}

/**
 * Returns true if the slug is a valid wiki page name.
 * Must be non-empty, alphanumeric only, max 100 chars.
 */
export function isValidSlug(slug) {
  if (!slug || slug.length === 0) return false;
  if (slug.length > 100) return false;
  return /^[a-zA-Z0-9]+$/.test(slug);
}
