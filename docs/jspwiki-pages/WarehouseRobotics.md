---
type: article
cluster: warehouse-automation
tags: [warehouse, robotics, amr, agv, cobot, automation, logistics]
date: 2026-03-18
status: active
summary: Warehouse robotics — AMRs, AGVs, robotic picking arms, and cobots that move, store, pick, and pack goods inside fulfilment centres
related: [WarehouseAutomationHub, WarehouseManagementSystems, AutomatedStorageAndRetrieval, ConveyorAndSortingTechnology, WarehouseAiAndMl, WarehouseAutomationLimitations]
---
# Warehouse Robotics

Warehouse robotics encompasses the physical machines that move, retrieve, pick, and handle goods inside a distribution or fulfilment centre. The field has expanded rapidly since the mid-2010s, driven by falling sensor costs, improved computer vision, and advances in motion planning.

## Robot Categories

### Autonomous Mobile Robots (AMRs)

AMRs navigate autonomously using on-board sensors (LiDAR, cameras, ultrasonic) and simultaneously map their environment (SLAM — Simultaneous Localisation and Mapping). They do **not** require fixed infrastructure like floor tape or magnetic rails.

**Goods-to-person AMRs** (the dominant model):  
Robots carry entire shelving pods to stationary pickers at ergonomic workstations. The picker touches only the items needed for the order; all travel time is eliminated.

- **Key vendors:** Kiva/Amazon Robotics, Geek+, 6 River Systems (Shopify), Locus Robotics, Fetch Robotics (Zebra)
- **Throughput:** 300–600 picks/hour/station (vs ~60–120 for manual pick-to-cart)
- **Density:** storage density increases 2–4× because aisles can be narrow — robots work the aisles, humans don't.

**Follower AMRs** (cart-follower / collaborative):  
Robots follow a human picker, eliminating manual cart pushing. Lower throughput gain than goods-to-person but very low infrastructure cost.

### Automated Guided Vehicles (AGVs)

AGVs follow fixed paths defined by magnetic tape, wire in the floor, or laser reflectors. They are older and less flexible than AMRs but extremely reliable and well understood.

- **Use cases:** pallet transport between dock, staging, and storage; tow trains for replenishment
- **Key vendors:** Jungheinrich, Dematic (KION), Daifuku, Toyota Industries
- **Limitations:** path changes require physical infrastructure modifications; poor at dynamic obstacle avoidance

### Robotic Picking Arms

Articulated robotic arms (4–6 degrees of freedom) equipped with end-effectors (suction cups, grippers, soft robotic hands) attempt to grasp individual items from bins or shelves.

**The hardest problem in warehouse robotics:** picking arbitrary items from an unstructured environment (bin picking) requires:
- Computer vision to identify and locate items
- Grasp planning to determine a stable grip point
- Motion planning to approach and extract without collision
- Compliance to handle fragile or irregular items

| Arm type | Best at | Struggles with |
|---|---|---|
| Suction (vacuum) | Flat, non-porous, rigid | Mesh bags, perforated items, heavy cartons |
| Parallel jaw gripper | Uniform objects | Irregular shapes, soft goods |
| Soft robotic (pneumatic fingers) | Fragile, irregular | High speed, heavy payloads |
| Magnetic | Metal cans, sheet stock | Non-ferrous materials |

- **Key vendors:** Mujin, Covariant, Symbotic, RightHand Robotics, Plus One Robotics
- **Throughput:** ~600–1,200 picks/hour for simple items; drops sharply with SKU diversity
- **Accuracy rates:** ~98–99.5% — still below human pickers on difficult SKUs

### Collaborative Robots (Cobots)

Cobots are designed to operate alongside humans safely, with force-limiting joints that stop on contact. In warehouse settings they are used for:
- Assisted packing and lidding
- Label application
- Palletising (layer-by-layer pallet building)
- Quality inspection assistance

- **Key vendors:** Universal Robots, Fanuc CRX, ABB GoFa, KUKA LBR

### Palletising and Depalletising Robots

High-speed robotic cells that build or break down pallets at inbound docks and outbound lanes. These are among the most mature and highest-ROI robotics deployments in warehousing.

- Typical cycle time: 800–1,200 cycles/hour for layer palletising
- Vision-guided depalletising handles mixed SKU pallets (the harder direction)

## Fleet Management

Large AMR deployments require a **Fleet Management System (FMS)** that:
- Assigns tasks to robots in real time based on battery state, proximity, and priority
- Manages traffic to prevent deadlock and bottlenecks
- Coordinates charging schedules to maintain continuous coverage
- Reports utilisation, exception counts, and uptime metrics to the [WMS](WarehouseManagementSystems)

## Safety Standards

- **ISO 3691-4** — industrial trucks (including AGVs)
- **ISO/TS 15066** — collaborative robot safety
- **ANSI/RIA R15.08** — industrial mobile robots (AMRs), US standard
- **IEC 62061 / ISO 13849** — safety integrity levels for control systems

## See Also

- [Warehouse Automation Hub](WarehouseAutomationHub)
- [Automated Storage and Retrieval Systems](AutomatedStorageAndRetrieval) — fixed infrastructure systems robots work alongside
- [Warehouse AI and ML](WarehouseAiAndMl) — computer vision and motion planning that power picking arms
- [Warehouse Automation Limitations](WarehouseAutomationLimitations) — where robotics still falls short
- [Conveyor and Sorting Technology](ConveyorAndSortingTechnology) — robotic arms feed into conveyor lines
