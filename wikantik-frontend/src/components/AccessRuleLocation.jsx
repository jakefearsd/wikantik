// Where an access rule the server cannot read sits (#104), in the editor's own coordinates: a line of the body (the
// first line after the frontmatter is line 1), or the frontmatter field it is in, since the frontmatter is edited as
// fields. The server sends one of `line` or `field`; a rule in a frontmatter line no field owns has neither.
export default function AccessRuleLocation({ line, field }) {
  if (line != null) return <>Line {line}</>;
  if (field) return <>Frontmatter field <code>{field}</code></>;
  return <>Frontmatter</>;
}
