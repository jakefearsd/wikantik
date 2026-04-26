---
title: Graph Algorithms Deep Dive
type: article
cluster: data-structures
status: active
date: '2026-04-25'
tags:
- graph-algorithms
- bfs
- dfs
- dijkstra
- shortest-path
- max-flow
summary: BFS, DFS, Dijkstra, A*, Bellman-Ford, Floyd-Warshall, max-flow,
  topological sort — what each is for, the gotchas, and the production cases
  where each is the right pick.
related:
- DataStructures
- DynamicProgrammingPatterns
- DistributedComputingAlgorithms
- GraphDatabaseFundamentals
hubs:
- DataStructures Hub
---
# Graph Algorithms Deep Dive

Graph algorithms show up in routing, scheduling, social networks, code analysis, search, recommendation systems. Most production work uses a small subset; knowing them well is the difference between "we should think about this" and "I know what to reach for."

## Graph representations

Two common forms; pick by access pattern:

- **Adjacency list** — `Dict[Node, List[Node]]`. Most common. Iterate neighbours in `O(deg)`. Memory `O(V + E)`. Default for sparse graphs.
- **Adjacency matrix** — `bool[V][V]` (or `int` for weights). `O(1)` neighbour test; `O(V)` to iterate neighbours. Memory `O(V²)`. Use for dense graphs (`E ≈ V²`) or when "is there an edge" is the hot operation.

For very large graphs (millions of nodes), specialised structures: CSR (Compressed Sparse Row), graph database storage, or distributed graph stores.

## Traversal

### BFS (Breadth-First Search)

```python
def bfs(graph, start):
    visited = {start}
    queue = [start]
    while queue:
        node = queue.pop(0)  # use deque in production
        for neighbour in graph[node]:
            if neighbour not in visited:
                visited.add(neighbour)
                queue.append(neighbour)
```

Visits nodes in order of distance from the start. Uses: shortest path in unweighted graphs, level-order processing.

Common production uses: web crawler, BFS-based maze solving, finding shortest path in graphs where edges have unit weight, propagating updates outward in a hierarchy.

### DFS (Depth-First Search)

```python
def dfs(graph, start, visited=None):
    visited = visited or set()
    visited.add(start)
    for neighbour in graph[start]:
        if neighbour not in visited:
            dfs(graph, neighbour, visited)
```

Goes deep before wide. Uses: detecting cycles, topological sort, finding strongly-connected components, backtracking algorithms.

For deep graphs, recursion can blow the stack. Iterative version with an explicit stack is safer for production.

## Topological sort

Order nodes such that each comes before its dependents. Only works on DAGs (no cycles).

Two algorithms:

- **Kahn's algorithm** — BFS-based. Find nodes with no incoming edges; emit them; remove them; repeat. Linear time.
- **DFS-based** — DFS, emit nodes on finish. Reverse the order.

Used in: build systems (compile X before Y), task schedulers, dependency resolution (npm, pip), data pipeline orchestration (Airflow DAGs).

## Shortest path

### Dijkstra

For non-negative edge weights. Uses a priority queue; in each step, picks the closest unvisited node.

```python
def dijkstra(graph, start):
    dist = {start: 0}
    pq = [(0, start)]
    while pq:
        d, node = heapq.heappop(pq)
        if d > dist.get(node, float('inf')): continue
        for neighbour, weight in graph[node]:
            new_dist = d + weight
            if new_dist < dist.get(neighbour, float('inf')):
                dist[neighbour] = new_dist
                heapq.heappush(pq, (new_dist, neighbour))
    return dist
```

`O((V + E) log V)` with a binary heap; `O(V² + E)` with naive priority queue (sometimes faster for dense graphs).

Production uses: routing (Google Maps, OSRM), network routing protocols (OSPF), game pathfinding.

### A*

Dijkstra with a heuristic. Searches preferentially in the direction of the goal.

```python
priority = current_distance + heuristic(node, goal)
```

Heuristic must be admissible (never overestimate true distance). For grid pathfinding, Manhattan or Euclidean distance works.

Faster than Dijkstra when there's a useful heuristic. Used in robotics, game AI, GPS routing. The choice of heuristic dominates performance.

### Bellman-Ford

Handles negative edges (Dijkstra doesn't). Detects negative cycles. `O(VE)` — slower.

Used: detecting arbitrage in currency exchange (negative-cost cycle), edge cases of routing protocols, when graphs might have negative weights.

### Floyd-Warshall

All-pairs shortest paths. `O(V³)` time and space.

Used: small graphs where you need all pairwise distances. Network analysis, social-network centrality. For larger graphs, run Dijkstra from each source.

### Johnson's algorithm

All-pairs shortest paths in graphs with negative edges. Uses Bellman-Ford to reweight, then runs Dijkstra from each source. `O(VE + V² log V)`.

Niche but the right tool when you need it.

## Connectivity

### Connected components (undirected)

DFS or union-find. Linear time.

Used: clustering on graph data (find connected user groups, identify isolated subnetworks), gameplay analysis (regions in maps).

### Strongly connected components (directed)

Tarjan's or Kosaraju's algorithm. Linear time.

Used: code analysis (mutually-recursive function groups), social-network "communities," compiler register allocation.

### Articulation points / bridges

Single nodes / edges whose removal disconnects the graph. Used for vulnerability analysis ("if this server fails, what's affected"), social-network analysis ("influencer detection"), VLSI design.

Tarjan's algorithm computes both in linear time.

### Union-Find / Disjoint Set Union

Maintains a partition of elements; supports `union(x, y)` and `find(x)` to test which set an element is in. With path compression and union by rank: practically `O(α(n))` per operation, where `α` is the inverse Ackermann function (basically constant).

Used: Kruskal's MST, connected-component computation, percolation studies, image segmentation.

## Minimum spanning tree

Connect all nodes with minimum total edge weight.

- **Kruskal's** — sort edges by weight; add to MST if it doesn't form a cycle (union-find). `O(E log E)`.
- **Prim's** — start at one node; greedily add the minimum-weight edge to an unvisited node. `O((V + E) log V)`.

Used: network design (cable laying, road planning), clustering algorithms (single-linkage hierarchical), image segmentation.

## Max flow / min cut

Maximum flow from source to sink in a flow network. Min-cut equals max-flow (by duality).

Algorithms:

- **Ford-Fulkerson** — finds augmenting paths; complexity depends on path-finding strategy.
- **Edmonds-Karp** — Ford-Fulkerson with BFS for paths. `O(VE²)`.
- **Dinic's** — `O(V² E)`. Modern default for general flows.
- **Push-relabel** — different approach; competitive in practice.

Used: bipartite matching (assign workers to tasks, match job applicants to jobs), image segmentation, network reliability, sports tournament problems disguised as flow.

Min-cut shows up in: clustering, image segmentation (graph-cut algorithms), network attack analysis.

## Specialised problems

### Bipartite matching

Match nodes from one set to another. Hungarian algorithm for weighted; Hopcroft-Karp for unweighted.

Used: assignment problems, dating apps, ad allocation, kidney-exchange chains.

### Traveling Salesman (TSP)

Visit every node exactly once, minimum cost. NP-hard. Solved exactly with held-karp DP `O(n² 2^n)` for `n ≤ 20`. Approximation algorithms (Christofides) for metric TSP get within 1.5× optimal.

In practice: Concorde solver handles thousands of nodes; OR-Tools handles bigger with reasonable approximations.

### Centrality

Measures of node importance. Degree centrality (just count edges), betweenness centrality (fraction of shortest paths passing through), eigenvector centrality (PageRank-like), closeness centrality.

Used: social-network analysis, infrastructure criticality, biological network analysis.

PageRank is essentially eigenvector centrality on the web graph. Compute with power iteration; converges in tens of iterations.

### Graph coloring

Assign colours to nodes such that adjacent nodes have different colours. NP-hard in general.

Used: register allocation in compilers, scheduling (no two conflicting events at same time), map colouring.

Greedy algorithms are good enough in practice for many cases.

## Performance considerations

For huge graphs:

- **Sparse representations** (CSR) — packed arrays beat hashmaps for cache.
- **Out-of-core algorithms** — graph doesn't fit in memory; access patterns matter.
- **Distributed graph processing** — Pregel (Google), Giraph (Apache), GraphX (Spark). Bulk-synchronous parallel model.
- **GPU graph algorithms** — for some operations (PageRank, BFS at scale), GPUs win. Frameworks: cuGraph, Gunrock.

Production graph database: Neo4j, JanusGraph, TigerGraph, ArangoDB. Each has built-in implementations of these algorithms; rarely write them yourself in production. See [GraphDatabaseFundamentals].

## What you actually need

For day-to-day engineering work, knowing this small set well covers most cases:

- BFS, DFS, topological sort.
- Dijkstra (and roughly when to upgrade to A*).
- Connected components / union-find.
- The fact that max-flow and bipartite matching exist and that a library can solve them.

Beyond this is specialisation. If you find yourself reaching for "graph coloring with K colours" or "minimum spanning tree on a 100M-node graph," you're in territory where the literature has answers and the algorithm choice matters.

## Further reading

- [DataStructures] — graphs in context
- [DynamicProgrammingPatterns] — DP on graphs (TSP, edit distance, etc.)
- [DistributedComputingAlgorithms] — graph algorithms across machines
- [GraphDatabaseFundamentals] — purpose-built graph storage
