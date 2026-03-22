---
type: article
tags:
- woodworking
- CAD
- CAM
- software
- digital-fabrication
- hobby
date: '2026-03-20'
cluster: hobby-woodworking
status: active
summary: 'CAD, CAM, and laser software for hobby woodworkers: Fusion 360, SketchUp,
  VCarve Pro, LightBurn, and the design-to-cut workflow'
related:
- HobbyWoodworkingInTheTwentyFirstCentury
- CncRoutersForTheHobbyWorkshop
- LaserCuttersAndEngraversForWood
- ThreeDeePrintingMeetsWoodworking
---
# Digital Design Tools for Woodworkers

The gap between a napkin sketch and a finished piece has been bridged by software. Modern CAD (Computer-Aided Design) and CAM (Computer-Aided Manufacturing) tools let hobbyists design in 3D, simulate assembly, generate cut lists, and produce toolpaths for CNC routers and laser cutters — all before touching a piece of wood.

## CAD Software for Woodworking

### Fusion 360 (Autodesk)
- **Cost**: Free for personal use (with limitations)
- **Strengths**: Full parametric 3D modelling, integrated CAM for CNC, excellent for joinery design
- **Weaknesses**: Cloud-dependent, steep learning curve, overkill for simple projects
- **Best for**: Serious hobbyists who also use CNC

### SketchUp (Trimble)
- **Cost**: Free web version; $120/year for desktop
- **Strengths**: Intuitive push-pull modelling, huge community of woodworkers sharing models
- **Weaknesses**: Weak CAM integration, awkward for curved surfaces
- **Best for**: Furniture design and planning, visual prototyping

### FreeCAD
- **Cost**: Free and open-source
- **Strengths**: Parametric modelling, no vendor lock-in, active development
- **Weaknesses**: Less polished UI, smaller woodworking community
- **Best for**: Users who value open-source principles

### Shapr3D
- **Cost**: Free tier; $25/month for full features
- **Strengths**: iPad-native with Apple Pencil support, direct modelling feels like sculpting
- **Weaknesses**: Limited CAM, primarily design-focused
- **Best for**: Designers who think better with a stylus

## CAM Software for CNC

### VCarve Pro (Vectric)
- **Cost**: $700 one-time
- **Strengths**: The hobby CNC standard. Excellent 2D/2.5D toolpath generation, V-carving, inlay
- **Weaknesses**: Limited 3D capability (need Aspire for full 3D)
- **Best for**: Sign making, decorative work, furniture joinery

### Carbide Create (Carbide 3D)
- **Cost**: Free basic; $120/year for Pro
- **Strengths**: Tight integration with Shapeoko machines, approachable for beginners
- **Weaknesses**: Fewer advanced features than VCarve
- **Best for**: Shapeoko owners, CNC beginners

### Fusion 360 CAM
- **Cost**: Included with Fusion 360
- **Strengths**: Full 3D toolpath support, adaptive clearing, direct integration with design
- **Weaknesses**: Complex setup, can be confusing for woodworkers used to VCarve's approach
- **Best for**: Advanced users doing 3D carving and complex multi-axis work

## Laser Software

### LightBurn
- **Cost**: $60 one-time
- **Strengths**: The standard for laser work. Supports most CO2 and diode lasers. Excellent vector and raster control.
- **Best for**: Anyone with a laser cutter

### LaserGRBL
- **Cost**: Free
- **Strengths**: Lightweight, good for simple engraving
- **Weaknesses**: Windows only, limited compared to LightBurn

## The Design-to-Cut Workflow

```
Idea → Sketch → CAD model → Technical drawing → CAM toolpath → G-code → Machine → Wood
```

In practice:
1. **Sketch** the piece on paper or tablet
2. **Model** in Fusion 360 or SketchUp to validate proportions and joinery
3. **Generate drawings** with dimensions and a cut list
4. **Create toolpaths** in VCarve or Fusion CAM for CNC-cut parts
5. **Export G-code** and load into machine controller
6. **Cut and assemble** — digital precision meets physical craft

The key insight is that steps 1–5 cost nothing but time. You can iterate, make mistakes, and refine entirely in software before committing expensive hardwood to the machine.

## See Also

- [Hobby Woodworking in the Twenty-First Century](HobbyWoodworkingInTheTwentyFirstCentury) — Cluster hub
- [CNC Routers for the Hobby Workshop](CncRoutersForTheHobbyWorkshop) — The machines that execute CAM-generated toolpaths
- [Laser Cutters and Engravers for Wood](LaserCuttersAndEngraversForWood) — LightBurn and laser-specific software
- [3D Printing Meets Woodworking](ThreeDeePrintingMeetsWoodworking) — CAD skills transfer directly to 3D printing jig design
