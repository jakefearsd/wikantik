---
type: article
cluster: operations-research
tags:
  - operations-research
  - history
  - world-war-two
  - mathematics
date: 2026-03-17
related:
  - OperationsResearchHub
  - LinearProgrammingFoundations
  - IntegerAndCombinatorialOptimization
status: active
summary: The history of operations research from its WWII origins through post-war industrialization to the modern discipline
---
# History of Operations Research

Operations Research has one of the clearest origin stories in applied science: it was born of wartime necessity, flourished in the peacetime economy, and eventually became so embedded in industrial and commercial practice that its presence became invisible. This article traces that arc from the first operational analysis groups of World War II through the formalization of the discipline and its explosive post-war growth.

## Origins in World War II (1939–1945)

### The Problem of Overwhelming Complexity

Military operations in modern industrialized warfare generate staggering coordination problems. Moving troops, supplies, and equipment across continents; allocating scarce resources across competing theaters; timing offensives to exploit fleeting windows — these decisions had always been made by experienced commanders relying on judgment and precedent. By 1939, the scale and technological sophistication of warfare had exceeded what intuition alone could handle.

The immediate trigger was the German Blitz and the Battle of Britain (1940). The Royal Air Force needed to understand not just aircraft and pilots, but *systems*: how radar stations should position their search beams, how interceptor controllers should be trained, how incoming raid data should flow from observers to command. These were not questions that engineering or military tradition could answer directly.

### Blackett's Circus

The response was to assemble small groups of scientists — physicists, mathematicians, biologists, psychologists — and embed them with operational commanders. The most famous was the group assembled around Patrick Blackett (later Lord Blackett), the Nobel Prize-winning physicist, at RAF Fighter Command and then Coastal Command. Blackett's team was nicknamed **Blackett's Circus** by the military officers who initially regarded them with skepticism.

Their method was empirical and quantitative. They collected data on real operations, built simple mathematical models of what was happening, and made recommendations based on analysis rather than tradition. Early results were dramatic:

- **Convoy depth charges:** Coastal Command aircraft were dropping depth charges with their fuses set to explode at 100 feet — a depth that made sense for U-boats that had time to dive deep. Blackett's team discovered that most aircraft spotted submarines on the surface or shallowly submerged. Resetting fuses to 25 feet increased the kill rate from roughly 1% to 7% of attacks — a sevenfold improvement at zero material cost.
- **Convoy escort allocation:** Analysis of convoy losses showed that larger convoys with the same number of escorts were not more vulnerable. The ratio of perimeter (where submarines could attack) to area (ships to protect) improves with convoy size. Switching from many small convoys to fewer large ones reduced losses significantly.
- **Night fighter illumination:** Work on optimal searchlight coverage patterns for night air defense improved intercept rates.

Similar groups were established across the Allied militaries. In the United States, the first OR group was formed in 1942, working first on antisubmarine warfare and later on bombing strategy, logistics, and ground operations. The term "operations research" (or "operational research" in British usage) was coined during this period.

### Scope Expands Through the War

As the war progressed, OR groups tackled increasingly ambitious problems:

- **Mining campaigns:** Statistical analysis of aerial mining in Japanese shipping lanes led to targeting strategies that sank more tonnage per sortie than bombing campaigns.
- **Bombing accuracy:** OR analysis of USAAF bombing revealed that tight formations designed to protect against fighters actually reduced bombing accuracy. The optimal formation for accuracy was looser than doctrine specified.
- **Logistics planning:** The Allied invasion of Normandy and the subsequent supply of forces across France required OR methods to model port throughput, truck fleet utilization, and stockpile drawdown. The famous "Red Ball Express" truck convoy system emerged from this analysis.
- **Submarine patrol patterns:** Game-theoretic analysis of submarine search problems — where to patrol, how to allocate search effort — laid groundwork for what would later become formal search theory.

## Post-War Formalization (1945–1960)

### The Mathematical Breakthrough: Linear Programming

The single most important post-war development was the formalization of **linear programming** by George Dantzig in 1947. Dantzig, working for the U.S. Air Force, devised both the mathematical framework (optimizing a linear objective subject to linear constraints) and the **simplex method** for solving it efficiently. This gave OR its first general-purpose computational tool.

Within a decade, LP was being applied to:
- Agricultural production planning
- Oil refinery scheduling
- Airline crew and fleet assignment
- Military logistics

The U.S. Air Force's Project SCOOP (Scientific Computation of Optimum Programs), for which Dantzig developed LP, aimed at mechanizing military logistics planning. It demonstrated that what had been a weeks-long manual planning exercise could be automated.

Almost simultaneously, the Russian mathematician Leonid Kantorovich had independently developed a similar theory for central economic planning (published in 1939 but largely unknown in the West until much later). Kantorovich would share the 1975 Nobel Prize in Economics with Tjalling Koopmans for this work.

### Institutionalization

The discipline formalized rapidly:

- **1947:** Dantzig's simplex method
- **1948:** RAND Corporation founded, housing a major OR research program
- **1952:** Operations Research Society of America (ORSA) founded
- **1953:** Journal of the Operations Research Society of America launched
- **1954:** The Institute of Management Sciences (TIMS) founded
- **1957:** Case Institute of Technology establishes the first OR graduate program

Universities began offering OR courses and degrees. Consulting firms sprang up to apply OR methods to industrial clients. The discipline attracted some of the best mathematical minds of the era.

### Key Early Results

The 1950s produced a burst of foundational theory:

- **Dantzig-Wolfe decomposition** (1960): Method for solving very large LP problems by decomposing them into smaller subproblems
- **Network flow algorithms:** Ford-Fulkerson (1956) for max flow; Dijkstra (1959) for shortest path
- **Integer programming:** Gomory's cutting plane method (1958) for problems requiring integer solutions
- **Dynamic programming:** Bellman's principle of optimality (1957), enabling sequential decision problems
- **Queuing theory formalization:** Building on Erlang's 1909 telephone traffic work, the 1950s produced rigorous M/M/1 and related queue models
- **Game theory application:** Von Neumann and Morgenstern's 1944 *Theory of Games and Economic Behavior* found immediate OR application in competitive strategy and military planning

## Industrial Expansion (1960–1990)

### Computing Enables Scale

The availability of mainframe computers transformed what OR could accomplish. Problems that had required weeks of hand calculation could be solved in hours, then minutes. This made OR economically viable for routine business decisions, not just occasional large-scale planning efforts.

The airline industry became the first major commercial adopter of large-scale OR:

- **American Airlines SABRE** (1960): The first computerized airline reservation system, incorporating OR-based seat allocation
- **Crew scheduling:** Airlines deployed LP and integer programming to assign crews to flights while minimizing cost and respecting work rules — problems with millions of variables
- **Revenue management:** American Airlines' pioneering yield management system (early 1980s) used LP to set prices dynamically, reportedly saving the airline $1.4 billion over three years

Manufacturing followed:

- **MRP (Material Requirements Planning):** OR-based production scheduling systems, deployed through the 1970s-80s in manufacturing
- **Just-in-Time inventory:** Toyota's production system, formalized in the 1970s, drew heavily on queuing theory and inventory models
- **Network optimization:** Oil companies used large-scale network flow models for pipeline and refinery operations

### The PERT/CPM Era in Project Management

The late 1950s saw two independent developments of network-based project scheduling methods:
- **CPM (Critical Path Method):** Developed jointly by DuPont and Remington Rand in 1957 for chemical plant construction
- **PERT (Program Evaluation and Review Technique):** Developed by the U.S. Navy for the Polaris submarine missile program in 1958

Both methods model projects as networks of activities and find the critical path — the sequence of tasks that determines total project duration. PERT added probabilistic time estimates to handle uncertainty. These methods became standard practice in construction, aerospace, and defense and remain widely used today.

## Modern OR (1990–Present)

### The Computational Revolution

Two developments transformed OR capabilities from the 1980s onward:

**Interior-point methods:** Narendra Karmarkar's 1984 polynomial-time algorithm for LP demonstrated that the simplex method, while practically fast, was not the only approach. Interior-point (barrier) methods proved competitive or superior for very large problems and are now standard in commercial solvers.

**Metaheuristics:** For problems too large or complex for exact methods, heuristic approaches flourished:
- Simulated annealing (Kirkpatrick et al., 1983)
- Genetic algorithms (Holland, formalized in the 1970s-80s)
- Tabu search (Glover, 1986)
- Ant colony optimization (Dorigo, 1992)

These methods find good-but-not-necessarily-optimal solutions to problems where exact algorithms are computationally infeasible.

**Solvers and modeling languages:** Commercial LP/IP solvers (CPLEX, Gurobi, XIMSS) and algebraic modeling languages (AMPL, GAMS, JuMP) made OR accessible to practitioners without deep algorithmic knowledge.

### Internet-Era Applications

The rise of the internet created entirely new OR application domains:

- **Internet routing:** Shortest-path algorithms run continuously across the global network
- **Search engine advertising:** Keyword auction systems are large-scale LP-based mechanisms
- **Ride-sharing dispatch:** Lyft and Uber solve real-time matching problems with tens of thousands of simultaneous parties
- **Last-mile logistics:** Amazon, FedEx, and UPS use vehicle routing optimization to schedule millions of deliveries daily
- **Financial portfolio optimization:** Markowitz mean-variance optimization (developed 1952, Nobel Prize 1990) is deployed at scale by every major investment manager

### OR and Machine Learning

Modern practice increasingly combines OR with machine learning:

- **Predict-then-optimize:** Use ML to forecast demand, prices, or costs; use OR to optimize given those forecasts
- **Learning to optimize:** Neural networks trained to find good solutions to combinatorial problems
- **Reinforcement learning for sequential decisions:** Policies trained via RL for problems where dynamic programming is intractable

The boundary between OR and AI has become genuinely blurry. Both Google DeepMind's protein structure prediction work and Amazon's supply chain systems combine techniques from both traditions.

## Key Figures

| Person | Contribution | Era |
|--------|-------------|-----|
| Patrick Blackett | Founded wartime OR, Coastal Command analysis | 1940s |
| George Dantzig | Linear programming, simplex method | 1947 |
| Leonid Kantorovich | Independent LP development; Nobel Prize 1975 | 1939/1975 |
| Richard Bellman | Dynamic programming | 1957 |
| L.R. Ford & D.R. Fulkerson | Max-flow min-cut theorem | 1956 |
| Edsger Dijkstra | Shortest path algorithm | 1959 |
| Ralph Gomory | Integer programming cutting planes | 1958 |
| Narendra Karmarkar | Interior-point LP algorithm | 1984 |

## See Also

- [Operations Research Hub](OperationsResearchHub) — Cluster overview
- [Linear Programming Foundations](LinearProgrammingFoundations) — The mathematics of Dantzig's breakthrough
- [Integer and Combinatorial Optimization](IntegerAndCombinatorialOptimization) — Beyond LP: when variables must be integers
