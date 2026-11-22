/**
 * A count for a text with a limit (the server stays the authority): silent until the text is within 90% of the limit,
 * then "n / limit", and a plain refusal once it is over. `id` lets the field point at it with aria-describedby.
 */
export default function CharCount({ length, limit, id, noun = 'text' }) {
  if (length < limit * 0.9) return null;
  const over = length > limit;
  return (
    <div id={id} className={over ? 'work-form-error work-char-count' : 'work-muted work-char-count'} role={over ? 'alert' : 'status'}>
      {over
        ? `This ${noun} is ${(length - limit).toLocaleString('en-US')} characters over the ${limit.toLocaleString('en-US')}-character limit. Shorten it to continue.`
        : `${length.toLocaleString('en-US')} / ${limit.toLocaleString('en-US')} characters`}
    </div>
  );
}
