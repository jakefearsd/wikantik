---
type: article
cluster: warehouse-automation
tags: [warehouse, conveyor, sorter, automation, logistics, material-handling]
date: 2026-03-18
status: active
summary: Conveyor and sorting technology — the fixed transport networks that move cartons and totes through fulfilment centres at high speed
related: [WarehouseAutomationHub, WarehouseManagementSystems, AutomatedStorageAndRetrieval, WarehouseRobotics, WarehouseAutomationLimitations]
---
# Conveyor and Sorting Technology

Conveyor and sorting systems form the **fixed transport backbone** of most large fulfilment centres. They move cartons, totes, and polybags between receiving docks, storage areas, picking stations, packing lines, and outbound shipping lanes — at speeds that would be impossible for a mobile fleet alone.

## Conveyor Types

### Belt Conveyors

The simplest and most common type. A continuous belt driven by rollers transports items along a fixed path.

- **Flat belt** — general transport; can handle irregular shapes
- **Incline / decline belt** — moves items between floor levels (typically 15–30° slope)
- **Curved belt** — changes conveyor direction without a transfer point
- **Speed:** 0.3–2.5 m/s for cartons; up to 3.5 m/s on high-speed lines

### Roller Conveyors

**Gravity rollers** — slope-fed, no motor; used in picking lanes and packing stations to allow cartons to flow forward as items are removed.  
**Powered roller (MDR — Motor Driven Roller)** — each roller zone has its own small motor; enables **zero-pressure accumulation**, where items queue without crashing into each other.

### Overhead Conveyors

Used primarily for apparel on hangers. Garments travel on hooks or carriers along an overhead track, enabling high-density sortation and order grouping without floor space consumption.

### Tilt-Tray and Cross-Belt Sorters

These are the **highest-speed, highest-accuracy** sortation technologies:

**Tilt-tray sorter:**  
Carriers with a flat tray travel in a closed loop. At the assigned sort destination, the tray tips to one side, sliding the item into a chute.
- Speed: up to 2.5 m/s
- Sort accuracy: >99.9%
- Best for: flat, stable items; polybags; small cartons

**Cross-belt sorter:**  
Each carrier has its own short belt conveyor running perpendicular to the loop direction. At the sort point, the belt activates and ejects the item laterally.
- Speed: up to 2.5 m/s
- Handles fragile, cylindrical, and soft items better than tilt-tray
- Best for: e-commerce parcel sortation, polybags, bottles
- **Key vendors:** Vanderlande, Beumer, Interroll, Dematic

### Sliding Shoe Sorters

A flat conveyor bed with diagonally-oriented shoes (slats) that slide laterally to divert items to angled take-away lanes. Gentler than tilt-tray, handles heavier cartons.

- Speed: up to 2.5 m/s
- Typical use: carton sortation to shipping lanes by carrier/zone

### Pop-Up Diverters (Transfer Modules)

Point-of-diversion modules installed inline. A set of rollers or wheels rises up through the conveyor surface at an angle, redirecting an item 30° or 90°.

- Used for: moderate-speed intersections, lane changes, induction onto sorter loops
- Lower cost than full sorter systems; suitable for simpler split/merge logic

## Key System Components

### Barcode / RFID Scanning

Every conveyor network includes **scan tunnels** — arched frames containing multiple barcode cameras or RFID antenna that read every passing item from all angles simultaneously. The scan result tells the sorter controller where to direct the item.

- No-read rate target: <0.5% (exceptions diverted to manual audit lane)
- Dimensioning/weighing/scanning (DWS) stations capture item dimensions and weight for manifesting

### Merge and Induction

Items from multiple upstream sources (picking stations, [AS/RS](AutomatedStorageAndRetrieval) outputs, returns processing) must **merge** onto the main sortation loop without collisions. **Induction** is the controlled process of placing items onto the sorter at correct spacing (gap control).

- Manual induction stations: humans slide items onto the loop one at a time (~800–1,200 items/hour per person)
- Automated induction: vision-guided robot arms or singulators replace human induction (~2,000–4,000 items/hour)

### Accumulation and Buffering

Conveyors include buffer loops and accumulation zones to smooth flow between fast upstream processes (picking) and slower downstream processes (packing). Zero-pressure accumulation (ZPA) via MDR ensures items queue without damage.

## Sorter Throughput Benchmarks

| Sorter type | Lines/hour | Load capacity | Typical use |
|---|---|---|---|
| Tilt-tray | 6,000–12,000 | up to 5 kg | Flat/polybag e-com |
| Cross-belt | 8,000–20,000 | up to 35 kg | General parcel |
| Sliding shoe | 4,000–9,000 | up to 50 kg | Heavy carton |
| Bomb bay (pocket sorter) | 15,000–30,000 | <2 kg | Polybag / apparel |

## Integration

Conveyor and sorter systems are controlled by a **Warehouse Control System (WCS)** or **Warehouse Execution System (WES)**, which receives sort orders from the [WMS](WarehouseManagementSystems) and translates them into real-time divert commands. Modern WES platforms also route work to [AMRs and robotic systems](WarehouseRobotics) from the same layer, creating a unified execution orchestration layer.

## See Also

- [Warehouse Automation Hub](WarehouseAutomationHub)
- [Automated Storage and Retrieval Systems](AutomatedStorageAndRetrieval) — AS/RS outputs feed conveyor networks
- [Warehouse Robotics](WarehouseRobotics) — robotic induction and depalletising at conveyor entry points
- [Warehouse Automation Limitations](WarehouseAutomationLimitations) — conveyor inflexibility and jam/exception challenges
