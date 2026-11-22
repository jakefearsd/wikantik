// Test helper: every attribute value rendered anywhere under `root`, flagged when it holds NaN or Infinity.
export function badAttributes(root) {
  const bad = [];
  root.querySelectorAll('*').forEach((el) => {
    [...el.attributes].forEach((a) => {
      if (/NaN|Infinity|undefined|null/.test(a.value)) bad.push(`${el.tagName}[${a.name}]=${a.value}`);
    });
  });
  return bad;
}
