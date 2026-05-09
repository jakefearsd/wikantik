---
cluster: warehouse-automation
canonical_id: 01KQ0P44RQWXE676639HTNEJYM
title: Last Mile Delivery Optimization
type: article
tags:
- logistics
- transportation
- algorithms
- tsp
- routing
summary: Advanced algorithms for last-mile delivery, focusing on TSP variants and route-density math for urban logistics.
auto-generated: false
date: 2025-02-13T00:00:00Z
---

# Last Mile Delivery: TSP Algorithms and Route Density

Last-mile delivery is the most expensive and complex segment of the supply chain. Optimizing it requires solving variants of the Traveling Salesperson Problem (TSP) under dynamic constraints like traffic, time windows, and vehicle capacity.

## 1. Algorithmic Foundations: TSP and VRP

### The Traveling Salesperson Problem (TSP)
The goal is to visit a set of stops ($n$) and return to the depot via the shortest possible route.
- **Complexity:** $O(n!)$—computationally impossible for large $n$ via brute force.
- **Heuristics:** Modern dispatchers use **Christofides Algorithm** (guarantees a solution within 1.5x of optimal) or **Ant Colony Optimization** for real-time routing.

### The Vehicle Routing Problem (VRP)
Extends TSP to a fleet of $m$ vehicles.
- **VRP with Time Windows (VRPTW):** Adds the constraint that stop $i$ must be reached between times $T_1$ and $T_2$.
- **Impact:** Tight time windows (e.g., 1-hour grocery delivery) drastically reduce "Route Density" and increase costs.

## 2. Route Density Math

Route Density is the primary metric for last-mile profitability.
- **Drop Factor:** The number of deliveries per stop. (Apartment complexes have high density; suburban houses have low density).
- **Stem Time:** Time spent driving from the depot to the first stop and from the last stop back to the depot.
- **On-Route Time:** Time spent driving between stops and performing the "door-to-door" action.

### The Optimization Formula
$$\text{Cost Per Drop} = \frac{(\text{Driver Wage} \times \text{Route Time}) + (\text{Distance} \times \text{Fuel Cost})}{\text{Total Drops}}$$

## 3. Concrete Example: Urban vs. Suburban Routing
1.  **Urban Route:** 50 stops within 2 miles. **Drop Density:** 25 stops/sq mile. **Result:** Low fuel cost, high "Last 100 Meter" labor cost (lobby/elevator wait).
2.  **Suburban Route:** 50 stops across 40 miles. **Drop Density:** 1.25 stops/sq mile. **Result:** High fuel and vehicle wear, low "Last 100 Meter" cost (front porch drops).

**Algorithmic Shift:** In urban areas, algorithms prioritize **Parking Optimization** (minimizing walking distance from a central van spot), whereas in suburban areas, they prioritize **Path Length** (minimizing driving miles).

## 4. Summary of Routing Techniques

| Technique | Usage | Benefit |
| :--- | :--- | :--- |
| **Nearest Neighbor** | Initial route build | Speed |
| **Tabu Search** | Local refinement | Escapes local optima |
| **Dynamic Rerouting** | Traffic incidents | Avoids delay cascades |
| **Drone Delivery** | High-value, light-weight | Bypasses road congestion |

## See Also
- [[VehicleRoutingProblem]]
- [[TransportationManagement]]
- [[MicroFulfillmentCenters]]
- [[SupplyChainResilience]]
