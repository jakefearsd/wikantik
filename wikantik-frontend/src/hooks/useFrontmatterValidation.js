import { useEffect, useRef, useState } from 'react';
import { metadataToYaml } from '../utils/frontmatterUtils';
import { api } from '../api/client';

// Debounced, race-safe, fail-open live frontmatter validation. Validates the SERIALIZED
// YAML (the exact bytes the save persists) so live results cannot disagree with the save gate.
export function useFrontmatterValidation(metadata, {
  enabled = true,
  debounceMs = 400,
  validate = api.validateFrontmatter,
} = {}) {
  const [violations, setViolations] = useState([]);
  const [validating, setValidating] = useState(false);
  const reqIdRef = useRef(0);

  const yaml = enabled ? metadataToYaml(metadata || {}) : null;

  useEffect(() => {
    if (!enabled) {
      setViolations([]);
      setValidating(false);
      return undefined;
    }
    const handle = setTimeout(() => {
      const myId = ++reqIdRef.current;
      setValidating(true);
      Promise.resolve(validate({ frontmatter: yaml }))
        .then((res) => {
          if (myId !== reqIdRef.current) return; // a newer request superseded this one
          setViolations((res && res.violations) || []);
        })
        .catch(() => {
          // fail-open: keep the last violations; the server stays the final gate
        })
        .finally(() => {
          if (myId === reqIdRef.current) setValidating(false);
        });
    }, debounceMs);
    return () => clearTimeout(handle);
  }, [yaml, enabled, debounceMs, validate]);

  return { violations, validating };
}
