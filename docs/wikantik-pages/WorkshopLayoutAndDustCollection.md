---
cluster: hobby-woodworking
canonical_id: 01KQ0P44ZA6MV4K074BQGMCP4Q
title: Workshop Layout and Dust Collection
type: article
tags:
- dust-collection
- workshop-design
- fluid-dynamics
- health-and-safety
summary: Technical guide for engineering workshop air systems, focusing on CFM requirements, static pressure loss in ducting, and HEPA filtration.
date: 2025-05-22
auto-generated: false
---

# Workshop Layout and Dust Collection Engineering

Effective dust collection is a matter of fluid dynamics, not just "suction." For a practitioner, the goal is to maintain a specific volume of air (CFM) at the tool's point of generation while overcoming the resistance (Static Pressure) of the collection system.

## 1. Defining CFM Requirements

The required Cubic Feet per Minute (CFM) depends on the tool's aperture and the volume of waste produced. Standard "4-inch" hobbyist ports are often the bottleneck.

| Machine Type | Min. CFM (at tool) | Target Velocity (FPM) | Recommended Port Size |
| :--- | :--- | :--- | :--- |
| Table Saw (Cabinet) | 550 - 650 | 4,000 (Main) / 3,500 (Branch) | 4" (Bottom) + 2.5" (Over-arm) |
| 12" Thickness Planer | 700 - 800 | 4,500 | 5" or 6" |
| 8" Jointer | 450 - 550 | 4,000 | 4" |
| Drum Sander | 600 - 700 | 4,500 | 2x 4" or 1x 6" |
| CNC Router (Small) | 300 - 400 | 4,000 | 3" or 4" |

**Note on Velocity:** You must maintain a minimum velocity of **3,500 - 4,000 FPM** in horizontal runs to prevent "duning" (dust settling in the pipes). For green or heavy chips (planers), aim for **4,500 FPM**.

## 2. Ducting Design and Static Pressure (SP) Loss

The total resistance your blower must overcome is the sum of losses from the machine port, every foot of pipe, every bend, and the filter itself.

### The Static Pressure Formula (Simplified)
$$
\text{Total SP Loss} = \text{Loss}_{\text{pipe}} + \text{Loss}_{\text{fittings}} + \text{Loss}_{\text{hood}} + \text{Loss}_{\text{filter}}
$$
### Key Loss Factors:
*   **Pipe Diameter:** Friction loss increases exponentially as pipe diameter decreases. A 4" pipe has roughly **twice** the SP loss of a 6" pipe for the same CFM.
*   **Flexible Hose:** Standard corrugated flex hose has **3x to 5x** the friction loss of smooth-walled rigid pipe. Keep flex runs under 5 feet.
*   **Bends:** Use "Long Radius" elbows. A 90-degree long-radius elbow is equivalent to roughly 6-10 feet of straight pipe in terms of SP loss. Avoid "T" junctions; use 45-degree "Y" entries (wyes) to merge branch lines.

### Design Rule of Thumb:
Always run the largest possible main trunk line (usually 6") as close to the machine as possible before stepping down to the tool port.

## 3. Micron-Level HEPA Filtration

The visible "chips" are a nuisance; the invisible sub-5 micron dust is the health hazard. Standard 30-micron or even 5-micron bags act as "dust distributors," capturing the big stuff while pumping fine particulate back into the shop air.

### Filtration Standards:
*   **HEPA (H13/H14):** Must capture 99.97% of particles down to **0.3 microns**.
*   **MERV 15/16:** Captures 85-95% of 0.3-1.0 micron particles. This is the minimum target for a professional-grade shop.

### Filter Maintenance and SP:
As a filter loads with dust, its SP resistance increases, which reduces CFM. 
1.  **Cyclone Separation:** Use a first-stage cyclone to drop 95% of waste into a bin before it hits the filter.
2.  **Surface Area:** Use pleated cartridge filters rather than bags. They provide 10x the surface area, allowing higher CFM at lower pressure drops.
3.  **Monitoring:** Install a **Magnehelic gauge** or simple U-tube manometer across the filter. A spike in pressure indicates it's time to clean the pleats.

## 4. Layout Strategy: The "Zonal" Approach

Instead of a single "spider web" of ducting, organize the shop into high-load and low-load zones.

1.  **The High-Load Zone:** Planer and Jointer. These should be closest to the dust collector to minimize SP loss during high-volume chip production.
2.  **The Sanding Station:** Requires high static pressure but lower CFM. Small-diameter hoses (1" to 2.5") are appropriate here, but they should be connected to a dedicated high-vac system (shop vac with HEPA) rather than the high-volume dust collector.
3.  **Air Scrubbers:** Ambient air cleaners should be positioned to create a "circular" airflow in the shop, moving air away from the assembly area and toward the filtration intake. They should cycle the entire shop air volume **6-8 times per hour**.

## 5. Summary of Practical Metrics

| Metric | Ideal Value |
| :--- | :--- |
| **Main Duct Velocity** | 4,000 FPM |
| **Filter Surface Area** | 1 sq ft per 2-3 CFM |
| **Blower HP (Minimum)** | 2 HP for 4" mains / 3 HP+ for 6" mains |
| **Max Flex Run** | < 5 feet |

Engineering for dust collection ensures that the "lungs" of the workshop—the collection system—match the "muscles" of the machinery.
