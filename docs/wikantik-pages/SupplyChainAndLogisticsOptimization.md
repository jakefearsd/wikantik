---
type: article
cluster: operations-research
tags:
  - operations-research
  - supply-chain
  - logistics
  - inventory
  - transportation
date: 2026-03-17
related:
  - OperationsResearchHub
  - LinearProgrammingFoundations
  - IntegerAndCombinatorialOptimization
  - StochasticModelsInOR
status: active
summary: OR applied to supply chains — economic order quantity, transportation problems, inventory theory, vehicle routing, and multi-echelon networks
---
# Supply Chain and Logistics Optimization

Supply chains are optimization problems made physical. Raw materials move to factories; factories ship to distribution centers; distribution centers deliver to retailers or customers. Every flow, every inventory decision, every routing choice involves tradeoffs that operations research has been formalizing since the 1950s. The aggregate value of supply chain OR is estimated in the hundreds of billions of dollars annually.

## Inventory Theory

Inventory exists to buffer against uncertainty and lead times. Hold too much and you pay carrying costs and risk obsolescence. Hold too little and you face stockouts, lost sales, and expediting costs. The core question: **how much to order, and when?**

### Economic Order Quantity (EOQ)

The **EOQ model** (Harris, 1913) is one of the oldest and most enduring results in OR. It assumes:
- Constant, deterministic demand at rate D units per year
- Fixed ordering cost K per order (independent of quantity)
- Holding cost h per unit per year (cost of capital, storage, insurance)
- Instantaneous replenishment

The total annual cost as a function of order quantity Q:

```
TC(Q) = K*(D/Q) + h*(Q/2)
       = ordering costs + holding costs
```

Ordering cost decreases with larger Q (fewer orders); holding cost increases (larger average inventory Q/2). Minimizing by taking the derivative and setting to zero:

```
Q* = sqrt(2KD/h)   (the EOQ formula)
```

The optimal order quantity balances ordering and holding costs. At Q*, both costs are equal — a property called the **square root law**.

**Example:** Demand D = 10,000 units/year, K = $100/order, h = $2/unit/year.
Q* = sqrt(2 × 100 × 10,000 / 2) = sqrt(1,000,000) = 1,000 units per order.
Order frequency: 10 orders per year, every 5.2 weeks.

### EOQ Extensions

- **EOQ with backorders:** Allow planned stockouts (backordering) when backorder cost is known
- **Production lot sizing:** Finite production rate rather than instantaneous replenishment
- **Quantity discounts:** Unit price decreases at order quantity breakpoints — EOQ formula extended to check each price tier
- **Multiple items with shared capacity:** Tradeoff between economies of ordering and storage constraints

### Safety Stock and Reorder Points

When demand is stochastic, the EOQ quantity is still used, but the **reorder point** includes a safety buffer:

```
Reorder point R = μ_L + z × σ_L
```

Where:
- μ_L = expected demand during lead time
- σ_L = standard deviation of demand during lead time
- z = safety factor corresponding to target service level (e.g., z = 1.645 for 95%)

**Safety stock** = z × σ_L. It is inventory held purely to protect against demand variability and lead time uncertainty. Reducing demand variability (through better forecasting or shorter lead times) can dramatically cut safety stock requirements.

### The Newsvendor Problem

The newsvendor (or critical fractile) model addresses single-period inventory decisions with uncertain demand:

- You order Q units at cost c per unit before seeing demand
- You sell at price p > c per unit, with unsold units salvaged at value s < c
- Demand D is random with distribution F

Optimal order quantity Q* satisfies:

```
F(Q*) = (p - c) / (p - s)
```

The right-hand side is the **critical ratio** — order up to the point where the probability of demand ≤ Q* equals the ratio of underage cost to total misalignment cost. This elegant result underlies fashion retail buying, option pricing, and any single-period capacity commitment.

## The Transportation Problem

The classic transportation problem is to ship goods from m supply origins to n demand destinations at minimum total cost, where each origin has a fixed supply capacity and each destination has a fixed demand requirement.

```
Minimize:    sum_i sum_j c_ij * x_ij
Subject to:  sum_j x_ij <= s_i   for all i   (supply constraints)
             sum_i x_ij = d_j    for all j   (demand constraints)
             x_ij >= 0
```

This is a network flow LP with totally unimodular structure — the LP always has an integer optimal solution. The **transportation simplex** or **MODI method** solves it efficiently.

**Applications:** Shipping costs between factories and warehouses; assigning workers to jobs (the **assignment problem**, a special case); balancing inventories across a network.

## Multi-Echelon Inventory Systems

Real supply chains are networks with multiple echelons: supplier → regional distribution center → local distribution center → retailer. Inventory decisions at each level affect the others. Optimizing each echelon independently leads to the **bullwhip effect** — demand variability amplifies as it moves upstream.

**Clark-Scarf model (1960):** The foundational multi-echelon inventory model. For serial supply chains (each stage feeds exactly one downstream stage), an optimal policy exists with a decomposition property: each stage can be optimized semi-independently using an echelon inventory concept.

**Key insight:** Coordinate inventory decisions across the chain by sharing demand information, not just order information. Point-of-sale data flowing upstream reduces the bullwhip effect dramatically.

## Vehicle Routing Problems

The **vehicle routing problem (VRP)** asks: given a depot and a set of customers with known demands and locations, find the minimum-cost set of routes for a fleet of vehicles such that each customer is visited exactly once and vehicle capacities are not exceeded.

VRP is NP-hard and generalizes the TSP. It is the core problem behind package delivery, grocery delivery, school bus routing, and service technician dispatch.

### VRP Variants

| Variant | Additional constraint |
|---------|-----------------------|
| CVRP | Vehicle capacity limits |
| VRPTW | Time windows for each customer |
| VRPP | Pickup and delivery |
| DVRP | Dynamic arrivals in real time |
| MDVRP | Multiple depots |

### Solution Approaches

**Exact methods:** Branch-and-cut for small instances (up to ~150 customers optimally). The cutting planes include capacity cuts and path elimination.

**Heuristics:** Clarke-Wright savings algorithm, sweep algorithm — fast but suboptimal.

**Metaheuristics:** Large neighborhood search (LNS), tabu search, adaptive LNS — the standard approach for large real instances. Modern LNS solvers handle thousands of customers in seconds.

### Real-World Scale

FedEx, UPS, and Amazon route millions of deliveries daily using VRP variants with real-time updates. UPS's ORION (On-Road Integrated Optimization and Navigation) system saves an estimated 100 million miles and 10 million gallons of fuel annually by optimizing delivery sequences.

## Network Design

At the strategic level, supply chain decisions involve which facilities to open, where to locate them, and how to configure the network. **Facility location** problems are integer programs that balance fixed opening costs against service costs.

**Uncapacitated Facility Location (UFL):** For each potential facility site, decide whether to open it (binary variable) and which customers to serve from it (continuous fraction), minimizing total fixed and service costs subject to each customer being fully served from open facilities.

Greedy approximation gives a solution within (1 + ln n) of optimal. LP rounding gives a constant-factor guarantee. Commercial MIP solvers handle instances with thousands of facilities and customers.

## Inventory Optimization in Practice

| Company | Application | Impact |
|---------|-------------|--------|
| Dell | Build-to-order, component inventory management | Reduced inventory from 30 days to 7 days (1990s) |
| Walmart | Vendor-managed inventory, cross-docking | Structural cost advantage over competitors |
| Amazon | Multi-echelon fulfillment network, same-day inventory | Enabled Prime delivery promises |
| Zara | Rapid replenishment, deliberate limited stock | High freshness, low markdown losses |
| P&G | Collaborative planning with Walmart (CPFR) | Reduced bullwhip, improved service levels |

## Humanitarian Logistics

OR methods are increasingly applied to humanitarian supply chains — disaster relief, vaccine distribution, refugee resettlement. The optimization objectives differ from commercial logistics:

- Minimize unmet need rather than cost
- Maximize equity of access across populations
- Handle extreme uncertainty (disaster timing, affected population location)
- Account for degrading infrastructure

The UN Humanitarian Response Depot network — pre-positioned supply caches around the world — was designed using facility location optimization.

## See Also

- [Operations Research Hub](OperationsResearchHub) — Cluster overview
- [Integer and Combinatorial Optimization](IntegerAndCombinatorialOptimization) — VRP and facility location as integer programs
- [Stochastic Models in OR](StochasticModelsInOR) — Safety stock, stochastic demand, newsvendor
- [Linear Programming Foundations](LinearProgrammingFoundations) — Transportation problem as LP
