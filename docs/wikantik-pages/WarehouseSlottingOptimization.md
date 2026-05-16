---
cluster: warehouse-automation
canonical_id: 01KQ0P44YPQ3H2TDPSP7JRGGX9
title: Warehouse Slotting Optimization
type: article
tags: [slotting, inventory-science, cog, logistics, warehouse-optimization]
summary: Technical framework for warehouse slotting optimization focusing on Center of Gravity (COG) math, Golden Zone picking, and Cube-per-Order Index (COI).
auto-generated: false
date: 2025-05-15
---

# Warehouse Slotting Optimization

Slotting optimization is the spatial arrangement of inventory to minimize travel time and maximize picking ergonomics. In high-density environments, slotting must be dynamically recalculated based on SKU velocity shifts and affinity patterns.

## The 'Golden Zone' Strategy

The **Golden Zone** is the vertical area between a picker's shoulders and mid-thigh (approximately 0.8m to 1.5m from the floor). Items placed here require the least amount of reaching or bending, resulting in the highest pick rates.

*   **Rule 1:** Place the top 20% of high-velocity SKUs in the Golden Zone.
*   **Rule 2:** Heavier items (over 10kg) should be placed at the lower end of the Golden Zone to minimize spinal load during retrieval.
*   **Rule 3:** Slow-moving (C-class) items should be relegated to the highest or lowest levels of the rack.

## Center of Gravity (COG) Math for Slotting

To minimize the total travel distance ($D_{total}$), the most active SKUs must be located at the **Center of Gravity** relative to the shipping/receiving docks.

### Single-Facility COG Formula
The ideal location$(X, Y)$for a high-velocity cluster is calculated by weighting the coordinates of pick locations by their demand volume:$$X = \frac{\sum (x_i \cdot v_i)}{\sum v_i}$$
$$Y = \frac{\sum (y_i \cdot v_i)}{\sum v_i}$$Where:
*$x_i, y_i$: Coordinates of potential slot$i$.
*$v_i$: Velocity (picks per period) of the SKU intended for that slot.

By centering high-velocity SKUs around the average exit point of the pick path, the "deadhead" travel time is minimized.

## Cube-per-Order Index (COI)

The **Cube-per-Order Index (COI)** is the primary technical metric used to rank SKUs for slotting. It balances the storage space required with the frequency of retrieval.$$COI = \frac{\text{Storage Space Occupied (Cube)}}{\text{Order Frequency (Picks)}}$$*   **Low COI:** These items are the "Best Candidates" for prime locations (near the dock). They take up little space but are picked often.
*   **High COI:** These items are "Space Hogs" with low activity. They should be moved to the back of the warehouse or deep storage.

## Slotting Feedback Loop: Check-Act-Verify

| Phase | Check (Input) | Act (Operation) | Verify (Output) |
| :--- | :--- | :--- | :--- |
| **Data Analysis** | Extract SKU velocity and COI from WMS. | Identify "Out-of-Slot" SKUs (A-items in C-slots). | Generate a "Slotting Heatmap." |
| **Execution** | Check for empty target slots in the Golden Zone. | Issue "Slotting Replenishment" tasks to move SKUs. | Confirm scan-to-scan completion of moves. |
| **Validation** | Monitor average pick path distance post-move. | Compare$D_{total}$ before and after the slotting change. | Verify a > 5% reduction in total travel time. |

## Affinity Slotting (SKU Correlation)
Beyond individual velocity, **Affinity** (Market Basket Analysis) identifies items frequently ordered together (e.g., SKU-A and SKU-B).
*   **Action:** Co-locate high-affinity pairs in adjacent slots or the same rack bay.
*   **Result:** Reduced "Inter-Aisle" travel, as the picker can complete multiple lines of an order at a single stop.
