const LOWER = 'lower';
const UPPER = 'upper';
const DIGIT = 'digit';
const OTHER = 'other';

function kindOf(ch) {
  if (ch === undefined) return null;
  if (/\p{Ll}/u.test(ch)) return LOWER;
  if (/\p{Lu}/u.test(ch)) return UPPER;
  if (/\p{Nd}/u.test(ch)) return DIGIT;
  return OTHER;
}

/** True when a space belongs after {@code cur} (the rule of TextUtil.beautifyString). */
function spaceAfter(curKind, nextKind) {
  return (curKind === UPPER && nextKind === DIGIT)
    || (curKind === LOWER && (nextKind === DIGIT || nextKind === UPPER))
    || (curKind === DIGIT && (nextKind === UPPER || nextKind === LOWER));
}

/**
 * De-CamelCases a page name the same way the server's {@code TextUtil.beautifyString} does:
 * {@code LowCostIndexFundInvesting} → "Low Cost Index Fund Investing", {@code HTMLParser} → "HTML Parser",
 * {@code Page123Test} → "Page 123 Test".
 */
export function beautify(name) {
  if (!name) return '';
  const chars = [...name];
  let out = '';
  let prevKind = LOWER;
  chars.forEach((cur, i) => {
    const curKind = kindOf(cur);
    const nextKind = kindOf(chars[i + 1]);
    if (prevKind === UPPER && curKind === UPPER && nextKind === LOWER) out += ' ';
    out += cur;
    if (spaceAfter(curKind, nextKind)) out += ' ';
    prevKind = curKind;
  });
  return out;
}
