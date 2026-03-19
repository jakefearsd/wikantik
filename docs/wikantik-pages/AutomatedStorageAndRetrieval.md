---
type: article
cluster: warehouse-automation
tags: [warehouse, asrs, storage, automation, logistics, goods-to-person]
date: 2026-03-18
status: active
summary: Automated Storage and Retrieval Systems (AS/RS) — fixed high-density storage technologies including unit-load cranes, mini-loads, carousels, and shuttle systems
related: [WarehouseAutomationHub, WarehouseRobotics, ConveyorAndSortingTechnology, WarehouseManagementSystems, WarehouseAutomationLimitations]
---
# Automated Storage and Retrieval Systems (AS/RS)

An **Automated Storage and Retrieval System (AS/RS)** is a fixed-infrastructure technology that automatically places loads into and retrieves them from predefined storage locations. AS/RS systems are the oldest form of warehouse automation, dating to the 1960s, and remain one of the most capital-intensive and space-efficient options available.

## System Types

### Unit-Load AS/RS (Stacker Cranes)

Designed for full pallets or large containers. A **stacker crane** travels along a rack aisle (single- or double-deep), with the mast spanning the full rack height.

- **Aisle height:** up to 45 m in high-bay warehouses
- **Throughput:** 30–60 dual cycles/hour per crane (one combined store + retrieve)
- **Weight capacity:** 500 kg to 2,000 kg per load
- **Key vendors:** Dematic, Swisslog, SSI Schäfer, Daifuku, Mecalux
- **Best for:** cold storage, automotive parts, beverages, and other high-cube, heavy-load environments

### Mini-Load AS/RS

Scaled-down stacker cranes for totes, trays, and cartons. Operates in the same rail-bound aisle format but for smaller loads (up to ~50 kg).

- **Throughput:** 100–500 totes/hour per aisle
- **Common in:** spare parts, e-commerce fulfilment, apparel

### Vertical Lift Modules (VLMs)

A VLM is a self-contained enclosed column with two columns of trays and a central extractor that retrieves the requested tray and presents it at an ergonomic access opening at the operator level.

- **Height:** 2 m to 16 m; maximises vertical space in existing buildings
- **Access time:** 20–40 seconds per tray
- **Storage density:** 60–80% floor space reduction vs open shelving
- **Key vendors:** Kardex, Hänel, Modula
- **Best for:** slow-to-medium velocity parts, spare parts rooms, healthcare supplies

### Vertical Carousels

A chain of shelves rotating on a vertical oval track — like a Ferris wheel for inventory. The target shelf rotates to the operator, who picks without bending or reaching.

- Simpler and less expensive than VLMs
- Lower density than VLMs but higher throughput
- **Best for:** parts rooms, MRO, print consumables

### Horizontal Carousels

Oval-shaped horizontal track carrying bins that rotate to bring the target bin to the operator. Usually deployed in banks of 2–4 with a single operator servicing all simultaneously.

- **Throughput:** 250–400 picks/operator/hour with an optimised pick sequence
- **Key vendors:** Hänel, Remstar (Kardex), BEUMER

### Shuttle Systems

A rack structure with one or more **shuttles** (small robotic vehicles) per aisle level that slide in and out to store and retrieve totes. Compared to stacker cranes, shuttles offer:

- Higher throughput via **multi-level shuttles** (one shuttle per level)
- More redundancy — failure of one shuttle doesn't take the whole aisle down
- Faster deployment (modular racking)

**Sub-types:**
- **Single-level shuttles** — one shuttle per level, handles that level only
- **Multi-level shuttles** — lift within the rack to change levels
- **Grid-based systems** (AutoStore, Ocado) — robots on a grid roof retrieve bins stacked below

**Grid / Cube Storage (AutoStore model):**  
Bins are stacked in a dense grid without aisles. Robots travel on top of the grid, dig down through stacks to retrieve target bins, and deliver them to workstations.

- **Density:** highest of any system — ~4× denser than conventional shelving, ~2× denser than shuttle racks
- **Throughput:** scales linearly with robot count
- **Key vendors:** AutoStore, Ocado, Exotec (Skypod)
- **Limitations:** retrieval latency increases for bins buried deep in the stack (hot SKUs must be near the top)

## Comparison

| System | Throughput | Density | CapEx | Flexibility |
|---|---|---|---|---|
| Unit-load crane | Medium | High | Very high | Very low |
| Mini-load crane | Medium-high | High | High | Low |
| VLM | Low-medium | Very high | Medium | Low |
| Carousel (horiz.) | Medium-high | Medium | Low | Low |
| Shuttle rack | High | High | High | Medium |
| Grid / cube | High (scalable) | Very high | High | Medium |

## Integration with Other Systems

All AS/RS types interface with the [WMS](WarehouseManagementSystems) through a **Warehouse Control System (WCS)**, which translates high-level pick/store orders into device commands. Totes retrieved from AS/RS systems typically flow onto [conveyor and sorting lines](ConveyorAndSortingTechnology) for onward routing to packing stations.

## See Also

- [Warehouse Automation Hub](WarehouseAutomationHub)
- [Warehouse Robotics](WarehouseRobotics) — AMRs and AS/RS serve similar goods-to-person goals but via different mechanisms
- [Conveyor and Sorting Technology](ConveyorAndSortingTechnology) — downstream transport of retrieved totes
- [Warehouse Automation Limitations](WarehouseAutomationLimitations) — high CapEx, inflexibility, and SKU constraints of AS/RS
