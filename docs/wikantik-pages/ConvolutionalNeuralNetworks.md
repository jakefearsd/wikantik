---
title: Convolutional Neural Networks
type: article
cluster: machine-learning
status: active
date: '2026-04-25'
tags:
- cnn
- deep-learning
- computer-vision
- image-recognition
summary: CNNs in 2026 — what they're still good for in a transformer-dominated
  world (efficiency, edge inference, narrow vision tasks), and the architecture
  decisions that decide whether they win or lose vs ViT.
related:
- NeuralNetworkArchitectures
- DeepLearningFundamentals
- LinearAlgebra
- GradientDescentAndOptimizers
hubs:
- MachineLearning Hub
---
# Convolutional Neural Networks

Convolutional neural networks were the architecture that proved deep learning could solve real-world vision (AlexNet, 2012). For a decade, they were the default for any image task. Vision Transformers (ViTs) and their descendants have largely overtaken them on benchmarks since 2020, but CNNs remain the right pick for several specific cases — and the inductive biases they exploit are still relevant whether or not the parameter count is a transformer.

## The core idea

A convolution slides a small filter (kernel) across the input, computing a weighted sum at each position. Stacking convolutions builds increasingly abstract feature detectors:

- **Layer 1**: edges, gradients.
- **Layer 2-3**: textures, simple patterns.
- **Layer 4-5**: object parts (eyes, wheels).
- **Deep layers**: object-level features (faces, cars).

Two mechanical tricks make this work:

- **Weight sharing** — the same filter slides across the whole image. Far fewer parameters than a fully-connected layer.
- **Translation equivariance** — a feature detected at one position is detected at any position. The network doesn't have to relearn "edges in the upper-left."

These inductive biases match the structure of natural images. For tasks where they don't (text, tabular data), CNNs lose to architectures with different priors.

## The standard architecture

A canonical CNN classifier:

```
Image (224×224×3)
→ Conv (3×3, 64 filters), ReLU, BatchNorm
→ Conv (3×3, 64 filters), ReLU, BatchNorm
→ MaxPool (2×2)            [output: 112×112×64]
→ Conv (3×3, 128 filters), ReLU, BatchNorm
→ Conv (3×3, 128 filters), ReLU, BatchNorm  
→ MaxPool                  [output: 56×56×128]
→ ...
→ Global average pool      [output: 1024]
→ Dense (num_classes)
→ Softmax
```

The pattern: progressively halve spatial dimensions; double channel dimensions; conv-conv-pool repeated. End with global pooling and a classifier.

This is the recipe behind VGG, ResNet, EfficientNet, and most production vision networks before transformers.

## ResNet: the 2015 inflection

ResNet introduced **residual connections** — a skip connection that adds the input of a block to its output. Allowed training of much deeper networks (152 layers and beyond) without vanishing gradients.

```
output = layer(x) + x   # the residual connection
```

The math is the same as a regular layer, but the gradient flows back through both the layer and the skip path. Identity mapping prevents gradient decay.

By 2026, residual connections appear in nearly every deep network — CNN or transformer. ResNet-50 remains a credible baseline for narrow vision tasks.

## Modern CNN families

- **EfficientNet** (2019, 2021 v2) — scales depth, width, and resolution together. Strong parameter efficiency.
- **ConvNext** (2022) — modernised CNN with transformer-inspired tweaks. Competitive with ViT at similar parameter counts; wins on inference cost.
- **MobileNet, ShuffleNet** — designed for edge inference; depthwise-separable convolutions cut compute drastically.
- **EfficientFormer, EdgeNeXt** — hybrid CNN-transformer designs targeting edge deployment.

For new vision projects in 2026:

- Reach for a pretrained ConvNext or EfficientNet for narrow image tasks.
- Reach for a vision transformer (DINOv2, EVA, CLIP) for general image tasks where you can afford the compute.
- Reach for MobileNet / EfficientFormer for edge / mobile.

## When CNNs still win

Vision transformers have surpassed CNNs on most benchmarks, but the calculus is more nuanced for production:

### Edge / mobile inference

CNNs are usually 5-10× more efficient than transformers at the same task on small images. Mobile CPUs and dedicated NPUs (Apple Neural Engine, Snapdragon) are often optimised for convolutions specifically.

For on-device tasks (face detection, photo segmentation, scanner OCR), CNNs remain the production default.

### Small datasets

Transformers typically need more data to train than CNNs. With < 10k labelled images, fine-tuning a pretrained CNN often beats training a ViT from scratch.

That said, fine-tuning a pretrained ViT (CLIP, DINOv2) on small data is competitive — the pretrained backbone covers the data hunger.

### Highly local pattern tasks

Some vision tasks are genuinely about local features: defect detection on a manufacturing line, OCR character recognition, simple medical imaging. CNN inductive biases match the task; no need for global attention; CNNs are faster.

### Specific dense prediction tasks

Some segmentation and detection benchmarks still see CNNs (or hybrid CNN-transformer architectures) winning. The U-Net family for medical imaging, YOLOv8/v9 for real-time detection.

## Object detection and segmentation

CNNs as feature extractors plus task-specific heads:

- **Detection** — Faster R-CNN, YOLO, RetinaNet. Detect bounding boxes around objects.
- **Semantic segmentation** — U-Net, DeepLab. Per-pixel class.
- **Instance segmentation** — Mask R-CNN. Per-pixel class + per-instance separation.
- **Panoptic segmentation** — combines semantic and instance.

Modern pipelines often replace the CNN backbone with a transformer (Swin, ViT). The task heads (detection, segmentation) remain similar.

## Training tips

CNN-specific training discipline:

- **Data augmentation matters more than architecture.** Random crops, flips, colour jitter, AutoAugment, RandAugment. Each adds points; the architectural difference between ResNet-50 and ResNet-101 is often dwarfed by augmentation.
- **Batch normalisation** has been a default since ResNet. GroupNorm is preferred for very small batches.
- **Mixup, CutMix** — input augmentation strategies that interpolate between samples.
- **Label smoothing** — soften the one-hot label to 0.9 / (rest divided by num_classes - 1). Small consistent gain.
- **Learning rate warmup + cosine decay** — same recipe as transformers.

## Inference optimisation

For deploying CNNs:

- **Quantisation** (INT8) — typically 2-4× faster on supported hardware with < 1% accuracy loss.
- **Pruning** — remove low-magnitude weights. Unstructured pruning compresses but doesn't speed up much; structured (channel) pruning does.
- **Knowledge distillation** — train a smaller model to imitate a larger one. Often recovers most of the accuracy gap.
- **TensorRT, ONNX Runtime, Core ML** — runtime libraries that fuse layers, optimise memory, target specific hardware. Substantial speed-ups for deployment.

A typical deployment chain: train in PyTorch → export to ONNX → optimise with TensorRT → serve. Each step gives maybe 1.5-3× speedup.

## What to skip

- **Old recipes (LeNet, AlexNet, VGG)** — historical interest only. Don't deploy.
- **Hand-designed architectures from scratch** — neural architecture search and pretrained backbones beat hand-tuning.
- **Custom normalisations** — BatchNorm or GroupNorm; the more recent variants rarely matter.

## A starting point for new vision projects

For a typical "I need to recognise / detect / segment something":

1. **Define the task and gather data.** Smallest part of the project to write down; biggest to do well.
2. **Use pretrained backbone.** ConvNext-base or DINOv2-base for a strong baseline. EfficientNet-B0 for size-constrained.
3. **Fine-tune on your data with strong augmentation.** RandAugment + label smoothing.
4. **Profile and quantise** for deployment.

This produces a competitive baseline in days instead of weeks. Push for transformer backbones only when you have data and compute.

## Further reading

- [NeuralNetworkArchitectures] — broader architecture context
- [DeepLearningFundamentals] — math and training
- [LinearAlgebra] — convolutions are linear maps
- [GradientDescentAndOptimizers] — training discipline
