#!/usr/bin/env python3
"""Summarise a PIT mutations.xml (run with -Pmutation, fullMutationMatrix on).

  bin/mutation-report.py <module> [survivors|tests|classes|all] [--desc] [--reports=pit-reports-auth]

survivors  every SURVIVED / NO_COVERAGE mutant, grouped by class, with the source line
tests      per test class: mutants it kills, mutants only it kills, and its surefire run time;
           then the test methods that cover mutants but kill none, and a greedy minimum test set
classes    mutation score per class, worst first
"""
import collections
import os
import re
import sys
import xml.etree.ElementTree as ET


def short_mutator(m):
    m = m.rsplit('.', 1)[-1]
    return m.replace('Mutator', '').replace('ReturnVals', 'Return')


def test_parts(t):
    # com.x.FooTest.[engine:junit-jupiter]/[class:com.x.FooTest]/[method:bar()]
    cls = re.search(r'\[class:([^\]]+)\]', t)
    meth = re.findall(r'\[(?:method|test-template|dynamic-test|test-factory):([^\]]+)\]', t)
    nested = re.findall(r'\[nested-class:([^\]]+)\]', t)
    c = cls.group(1) if cls else t.split('.[', 1)[0]
    if nested:
        c = c + '$' + '$'.join(nested)
    return c, (meth[0] if meth else '?')


def load(module):
    path = os.path.join(module, 'target', REPORTS, 'mutations.xml')
    root = ET.parse(path).getroot()
    out = []
    for m in root.iter('mutation'):
        def txt(tag):
            e = m.find(tag)
            return (e.text or '') if e is not None else ''
        out.append({
            'status': m.get('status'),
            'cls': txt('mutatedClass'),
            'method': txt('mutatedMethod'),
            'line': int(txt('lineNumber') or 0),
            'mutator': short_mutator(txt('mutator')),
            'desc': txt('description'),
            'file': txt('sourceFile'),
            'killing': [t for t in txt('killingTests').split('|') if t],
            'covering': [t for t in txt('coveringTests').split('|') if t],
        })
    return out


def source_line(module, cls, file, line, cache={}):
    pkg = cls.rsplit('.', 1)[0].replace('.', '/')
    p = os.path.join(module, 'src', 'main', 'java', pkg, file)
    if p not in cache:
        try:
            with open(p, encoding='utf-8', errors='replace') as f:
                cache[p] = f.read().splitlines()
        except OSError:
            cache[p] = []
    lines = cache[p]
    return lines[line - 1].strip() if 0 < line <= len(lines) else ''


def survivors(module, ms):
    by = collections.defaultdict(list)
    for m in ms:
        if m['status'] in ('SURVIVED', 'NO_COVERAGE'):
            by[m['cls']].append(m)
    for cls in sorted(by, key=lambda c: -len(by[c])):
        print(f'\n## {cls}  ({len(by[cls])})')
        for m in sorted(by[cls], key=lambda x: x['line']):
            tag = 'NOCOV' if m['status'] == 'NO_COVERAGE' else 'SURV '
            print(f"  {tag} L{m['line']:<4} {m['method']:<28.28} {m['mutator']:<26.26} "
                  f"| {source_line(module, m['cls'], m['file'], m['line'])[:110]}")
            if '--desc' in sys.argv:
                print(f"{'':42}-> {m['desc']}")


def classes(ms):
    tot = collections.Counter()
    killed = collections.Counter()
    for m in ms:
        if m['status'] in ('NON_VIABLE',):
            continue
        tot[m['cls']] += 1
        if m['status'] in ('KILLED', 'TIMED_OUT', 'MEMORY_ERROR'):
            killed[m['cls']] += 1
    rows = sorted(tot, key=lambda c: (killed[c] / tot[c], -tot[c]))
    for c in rows:
        print(f'  {100 * killed[c] / tot[c]:5.1f}%  {killed[c]:4}/{tot[c]:<4} {c}')
    T, K = sum(tot.values()), sum(killed.values())
    print(f'\n  total {K}/{T} = {100 * K / max(T, 1):.1f}%')


def surefire_times(module):
    d = os.path.join(module, 'target', 'surefire-reports')
    times = {}
    if not os.path.isdir(d):
        return times
    for f in os.listdir(d):
        if f.startswith('TEST-') and f.endswith('.xml'):
            try:
                r = ET.parse(os.path.join(d, f)).getroot()
                times[r.get('name')] = float(r.get('time') or 0)
            except ET.ParseError:
                pass
    return times


def tests(module, ms):
    kills = collections.defaultdict(set)      # test method -> mutant ids
    covers = collections.defaultdict(set)
    for i, m in enumerate(ms):
        for t in m['killing']:
            kills[test_parts(t)].add(i)
        for t in m['covering']:
            covers[test_parts(t)].add(i)
    killed_by = collections.defaultdict(set)
    for t, ids in kills.items():
        for i in ids:
            killed_by[i].add(t)
    unique = collections.Counter()
    for i, ts in killed_by.items():
        if len(ts) == 1:
            unique[next(iter(ts))] += 1
    times = surefire_times(module)

    per_cls = collections.defaultdict(lambda: [set(), 0, 0, set()])
    for t in set(kills) | set(covers):
        c = per_cls[t[0]]
        c[0] |= kills.get(t, set())
        c[1] += unique.get(t, 0)
        c[3].add(t[1])
    print(f"  {'kills':>6} {'only':>5} {'meths':>5} {'secs':>7}  test class")
    for cls in sorted(per_cls, key=lambda c: (per_cls[c][1], len(per_cls[c][0]))):
        k, u, _, meths = per_cls[cls]
        print(f'  {len(k):6} {u:5} {len(meths):5} {times.get(cls.split("$")[0], 0):7.2f}  {cls}')

    idle = sorted(t for t in covers if not kills.get(t))
    print(f'\n  test methods that cover mutants but kill none: {len(idle)}')
    for c, mth in idle:
        print(f'    {c.rsplit(".", 1)[-1]}.{mth}  (covers {len(covers[(c, mth)])})')

    # Greedy set cover over killed mutants: how few test methods keep every kill?
    remaining = set(killed_by)
    chosen = []
    while remaining:
        best = max(kills, key=lambda t: len(kills[t] & remaining))
        gain = kills[best] & remaining
        if not gain:
            break
        chosen.append(best)
        remaining -= gain
    alltests = set(kills) | set(covers)
    print(f'\n  greedy: {len(chosen)} of {len(alltests)} covering test methods keep all '
          f'{len(killed_by)} kills')
    chosen_cls = {c for c, _ in chosen}
    unused_cls = sorted({c for c, _ in alltests} - chosen_cls)
    if unused_cls:
        print('  test classes with no method in that set:')
        for c in unused_cls:
            print(f'    {c}')


REPORTS = 'pit-reports'


def main():
    global REPORTS
    for a in list(sys.argv):
        if a.startswith('--reports='):
            REPORTS = a.split('=', 1)[1]
            sys.argv.remove(a)
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    module = sys.argv[1]
    what = sys.argv[2] if len(sys.argv) > 2 else 'all'
    ms = load(module)
    if what in ('classes', 'all'):
        print('# Mutation score by class')
        classes(ms)
    if what in ('survivors', 'all'):
        print('\n# Survivors')
        survivors(module, ms)
    if what in ('tests', 'all'):
        print('\n# Test value')
        tests(module, ms)


if __name__ == '__main__':
    main()
