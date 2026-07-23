# Module Coupling

Inter-module dependency graph for the Wikantik reactor (Maven artifact edges,
`com.wikantik*` only). Module boundaries are enforced in code by
`DecompositionArchTest`; this graph is the visual companion.

![Module coupling graph](images/module-coupling.svg)

If the graph above is missing, the site was generated without graphviz — see the
raw [Graphviz source](images/module-coupling.dot).

## Class-level coupling

Class-level coupling (excessive imports, coupling-between-objects) is reported by
the PMD design rules in the [PMD aggregate report](pmd.html). Package-level
afferent/efferent metrics (JDepend) are intentionally not produced — no
JDK-25-capable tool exists.
