## Summary

<!-- One paragraph: what does this PR change, and why? -->

## Linked issues

<!-- Closes #N, refs #M, or "no related issue" -->

## What changed

<!-- Bullet list of concrete changes — files, classes, endpoints, schemas. -->

## Test plan

- [ ] Unit tests pass: `mvn clean install -T 1C -DskipITs`
- [ ] Integration tests pass: `mvn clean install -Pintegration-tests -fae`
- [ ] Manual verification (describe what you exercised in the running wiki):
- [ ] If schema changed: new `Vxxx__<desc>.sql` migration is included and idempotent
- [ ] If a `bin/` script changed: its `--help` still produces useful output
- [ ] If user-facing UI changed: tested in the browser (golden path + edge cases)

## Breaking changes / migration notes

<!-- Anything operators must do before/after deploying this change. -->

## Documentation

- [ ] README / docs updated where relevant
- [ ] CHANGELOG.md `[Unreleased]` section gains a line if user-visible

## Checklist

- [ ] No exceptions are silently swallowed (all catches log at least `LOG.warn`)
- [ ] No `git add -A` / no committed `target/` / no committed local-dev secrets
- [ ] CI is green
