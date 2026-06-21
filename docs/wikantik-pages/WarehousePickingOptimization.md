---
status: active
date: '2026-05-15'
summary: Technical analysis of warehouse picking optimization techniques, including
  S-Shape and Largest Gap routing, order batching strategies, and the impact of wave
  vs. waveless picking on throughput.
tags:
- warehouse-optimization
- picking
- routing
- batching
- logistics
- operations-research
type: article
relations:
- type: extension_of
  target_id: 01KQ0P44YKQTTF1M0KS2X7DB0Z
- type: component_of
  target_id: 01KQEKGDDVHTHY07CQ3YKSQ5PA
canonical_id: 01KRPPFJAB0VPQ4GRJA8WZ4AQS
cluster: operations-research
title: 'Warehouse Picking Optimization: Routing and Batching'
---

# Warehouse Picking Optimization: Routing and Batching

Picking—the process of retrieving items from storage to fulfill orders—accounts for up to **55% of total warehouse operating costs**. Minimizing travel distance is the primary objective of Operations Research (OR) in this domain.

## 1. Picker Routing Strategies

Routing determines the path a picker (human or AMR) takes through the warehouse aisles to collect a set of items.

### Standard Heuristics
*   **S-Shape (Transversal):** The picker enters an aisle and travels through its entire length. Only aisles containing items are entered. This is the simplest to implement and common in manual warehouses.
*   **Largest Gap:** The picker enters an aisle as far as the "largest gap" between items but does not traverse the entire aisle. They then return to the same cross-aisle. This often outperforms S-Shape when item density is low.
*   **Aisle-by-Aisle:** A variant where the picker visits every aisle in order, used primarily in very high-density environments.

### Optimal Routing (The TSP Approach)
Picker routing is a special case of the **Traveling Salesperson Problem (TSP)**. In a rectangular warehouse, the **Ratliff-Rosenthal Algorithm** (1983) provides an exact polynomial-time solution for finding the shortest path in a single-block warehouse. However, for multi-block warehouses (with multiple cross-aisles), the problem becomes $NP$-hard, necessitating metaheuristics like Ant Colony Optimization (ACO).

## 2. Order Batching

Batching combines multiple small orders into a single picking tour. The goal is to maximize **pick density** (items per meter traveled).

### Batching Models
*   **Seed Algorithms:** Select a "seed" order and add other orders that are geographically close in the warehouse.
*   **Savings Algorithms (Clarke-Wright):** Calculate the distance saved by combining two orders into one tour versus picking them separately.
*   **Fixed-Sized Batching:** Often used for AMRs (e.g., Locus, 6 River Systems) where the robot has a fixed capacity (totes).

## 3. Case Study: The Amazon "Kiva" Paradigm

Amazon's acquisition of Kiva Systems shifted the optimization from **Picker-to-Parts** to **Goods-to-Person (GTP)**.

**The Technique:** Instead of pickers walking, autonomous mobile robots (AMRs) bring entire shelving units (pods) to stationary picking stations.

**The Optimization:**
1.  **Pod Sequencing:** The Warehouse Control System (WMS) uses an integer program to decide which pod arrives at the station in which order to fulfill the highest number of active "batch" orders.
2.  **Station Balancing:** Dynamically routing pods to stations with the lowest queue to prevent idle time.
3.  **Reslotting:** When a pod returns to storage, the system calculates the optimal storage location based on the current demand "velocity" of its items—high-velocity pods are kept near the stations.

## 4. Wave vs. Waveless Picking

*   **Wave Picking:** Orders are released in discrete time blocks (waves). This allows for deep optimization of routing but introduces "dead time" at the end of each wave as workers wait for the next batch.
*   **Waveless (Continuous) Picking:** Orders are released dynamically as they arrive. This requires a **Real-Time Optimization (RTO)** engine that can constantly re-route pickers based on priority and proximity.

---
**See Also:**
- [Warehouse Slotting Optimization](WarehouseSlottingOptimization)
- [Supply Chain and Logistics Optimization](SupplyChainAndLogisticsOptimization)
- [Vehicle Routing Problem](VehicleRoutingProblem)
