// Pure, testable form serialization. `entries` is anything iterable of
// [key, value] pairs (FormData or a Map). Returns {spam, payload}.
export function serializeForm(entries) {
  const data = Object.fromEntries(entries);
  if ((data.company_url || '').trim() !== '') {
    return { spam: true, payload: null };
  }
  return {
    spam: false,
    payload: {
      name: (data.name || '').trim(),
      email: (data.email || '').trim(),
      use_case: (data.use_case || '').trim(),
    },
  };
}
