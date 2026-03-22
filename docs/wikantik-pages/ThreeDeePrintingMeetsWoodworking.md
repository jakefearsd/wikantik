---
type: article
tags:
- woodworking
- 3d-printing
- hobby
- jigs
- maker
- digital-fabrication
date: '2026-03-20'
cluster: hobby-woodworking
status: active
summary: How 3D printers create jigs, fixtures, workholding, and custom hardware that
  make woodworking projects easier and more precise
related:
- HobbyWoodworkingInTheTwentyFirstCentury
- CncRoutersForTheHobbyWorkshop
- DigitalDesignToolsForWoodworkers
---
# 3D Printing Meets Woodworking

The FDM (Fused Deposition Modelling) 3D printer might seem like an unlikely addition to a woodworking shop, but it has become one of the most practical tools in the modern hobbyist's arsenal — not for making finished pieces, but for making everything that *supports* making finished pieces.

## Why Woodworkers Print

Woodworkers don't 3D-print furniture. They print the things that make furniture possible:

### Jigs and Fixtures
- **Drill guides** — Print a block with a hole at exactly 15° for angled dowel joints
- **Router templates** — Curved or complex-profile templates that would take hours to make from MDF
- **Clamping cauls** — Curved cauls that distribute pressure evenly when gluing bent laminations
- **Hinge mortise jigs** — Perfectly sized for specific hinges, with built-in depth stops

### Workholding
- **Custom bench dogs** — Sized to fit your specific bench dog holes
- **Soft jaw inserts** — For holding irregularly shaped pieces in a vise without marring them
- **CNC workholding** — Vacuum pods, eccentric clamps, and toe clamps designed for your specific CNC table

### Functional Hardware
- **Knobs and pulls** — Drawer pulls, cabinet knobs, and furniture handles in any shape
- **Organisational inserts** — Custom dividers for drawers, trays for small parts
- **Dust collection adapters** — Fit any hose to any tool with a printed transition piece
- **Replacement parts** — Knobs, handles, and fittings for tools whose manufacturers have gone out of business

## Material Choices for Shop Use

| Material | Strength | Heat Resistance | Best For |
|----------|----------|----------------|----------|
| PLA | Moderate | Low (60°C) | Templates, organisational items |
| PETG | Good | Moderate (80°C) | Jigs, fixtures, outdoor use |
| ABS | Good | Good (100°C) | Parts near heat sources |
| Nylon | Excellent | Excellent | High-stress mechanical parts |
| TPU | Flexible | Moderate | Soft jaws, vibration dampening |

For most woodworking jigs, **PETG** offers the best balance of strength, ease of printing, and cost.

## Design Workflow

1. **Identify the problem** — A repetitive setup, an awkward clamping situation, a missing part
2. **Measure precisely** — Callipers are essential; every dimension matters
3. **Model in CAD** — Fusion 360 (free for hobbyists), TinkerCAD for simple items, or OpenSCAD for parametric designs
4. **Slice and print** — 0.2mm layer height is adequate for most shop fixtures
5. **Test and iterate** — Print a test fit before committing to the final version

The entire cycle from problem identification to working fixture typically takes 2–4 hours, including print time.

## Real-World Examples

### The Perfect Dovetail Marker
Print a saddle-shaped guide that sits on the end of a board and provides knife lines at exactly 1:6 (softwood) or 1:8 (hardwood) ratio. Cost: $0.30 in filament. Equivalent commercial brass marker: $45.

### CNC Spoilboard Inserts
Print threaded inserts that press-fit into a CNC spoilboard, providing anchor points for workholding. When they wear out, print more. Cost: pennies each.

### Dust Port Universal Adapter
A parametric adapter (designed in OpenSCAD) that transitions between any two diameters of dust collection hose. Share the file, change two numbers, print for your specific setup.

## See Also

- [Hobby Woodworking in the Twenty-First Century](HobbyWoodworkingInTheTwentyFirstCentury) — Cluster hub
- [CNC Routers for the Hobby Workshop](CncRoutersForTheHobbyWorkshop) — CNC workholding is a prime application for 3D printing
- [Digital Design Tools for Woodworkers](DigitalDesignToolsForWoodworkers) — The same CAD skills apply to both CNC and 3D printing
