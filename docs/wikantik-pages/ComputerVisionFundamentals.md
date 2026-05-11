---
summary: The fundamentals of computer vision — image representation, classical techniques,
  deep learning approaches, and the practical tradeoffs between speed, accuracy, and
  deployment constraints.
date: '2026-04-26'
cluster: machine-learning
related:
- ComputerVisionFundamentals
- TransformerArchitecture
- MlModelDeployment
- InferenceServing
canonical_id: 01KQ0P44NSVGZ41KK1TFWTPJF1
type: article
title: Computer Vision Fundamentals
tags:
- computer-vision
- machine-learning
- deep-learning
- cnn
- image-processing
status: active
hubs:
- MLHub
- AnomalyDetectionTechniques Hub
---
# Computer Vision Fundamentals

Computer vision teaches machines to extract meaning from images and video. Classical CV used hand-engineered features (edges, corners, SIFT). Modern CV is dominated by deep learning, particularly CNNs and increasingly transformers.

The shift from hand-engineered to learned features in 2012 (AlexNet) is the defining moment of modern CV.

## Image representation

Images are arrays of pixels. A grayscale image is a 2D array; a color image is 3D (height × width × channels, usually RGB).

Common formats:
- JPEG: lossy, small files
- PNG: lossless, larger
- WebP: modern, good compression
- RAW: sensor data, large

For ML: typically loaded as float arrays, normalized (often to [0,1] or standardized to mean 0).

## Classical techniques

### Edge detection

Sobel, Canny — find intensity gradients. Still used for preprocessing.

### Feature descriptors

SIFT, SURF, ORB — detect keypoints invariant to rotation and scale.

Used for:
- Image stitching (panoramas)
- Object matching
- SLAM (Simultaneous Localization and Mapping)

### Image segmentation

Watershed, k-means clustering — partition images into regions.

### Optical flow

Track pixel movement across video frames. Lucas-Kanade and Farnebäck are classics.

These techniques still matter for low-power, real-time, or interpretable systems.

## Convolutional Neural Networks (CNNs)

Inspired by biological vision. Convolutional layers learn local features; pooling layers reduce dimension; fully-connected layers classify.

Key architectures:
- **LeNet** (1998): the prototype
- **AlexNet** (2012): the breakthrough — ImageNet error dropped dramatically
- **VGG** (2014): deeper, simpler
- **ResNet** (2015): skip connections enable very deep networks
- **EfficientNet** (2019): compound scaling for accuracy/efficiency
- **ConvNeXt** (2022): modern CNN competitive with transformers

ResNet remains a strong default for image classification.

## Vision Transformers (ViTs)

Treat image as sequence of patches; apply transformer architecture.

ViTs need more data than CNNs to train from scratch but excel with pretraining.

Hybrid CNN-transformer architectures often work best in practice.

## Common tasks

### Image classification

Single label per image. The benchmark task; ImageNet is the canonical dataset.

### Object detection

Find and classify multiple objects per image. Bounding boxes + labels.

Architectures:
- **YOLO** (You Only Look Once): real-time, fast
- **Faster R-CNN**: two-stage, accurate
- **DETR**: transformer-based, end-to-end

### Semantic segmentation

Pixel-level classification. Each pixel gets a class label.

Architectures: U-Net, DeepLab, Mask R-CNN.

### Instance segmentation

Like semantic but distinguishes individual objects.

### Pose estimation

Find keypoints (joints, landmarks). Used for human pose, hand tracking, faces.

### Image generation

GANs, diffusion models generate novel images. Stable Diffusion, DALL-E.

## Pretraining and transfer learning

Most practical CV uses pretrained models:
- Take a model trained on ImageNet (or larger)
- Fine-tune on your task

This dramatically reduces data requirements. With 1000 examples, fine-tuning a pretrained model often beats training from scratch on millions.

## Data augmentation

Synthetically increase training data:
- Rotation, scaling, cropping
- Color jitter, brightness changes
- Mixup, CutMix (combining images)
- Random erasing

Aggressive augmentation helps with limited data.

## Deployment considerations

### Inference speed

CNNs run efficiently on GPUs. For mobile/edge:
- Quantization (int8, int4)
- Pruning
- Distillation
- Architecture choices (MobileNet, EfficientNet)

### Latency budgets

Real-time CV needs:
- 30+ FPS for video (33ms per frame)
- Sub-100ms for interactive applications

### Memory

Model weights + activation memory. Affects where the model can run.

## Common failure patterns

### Distribution shift

Models trained on ImageNet may fail on rotated, low-light, or domain-specific images.

### Adversarial examples

Tiny pixel perturbations can fool models. Robustness research is ongoing.

### Bias in training data

Models reflect biases in data. Face recognition has had documented racial bias issues.

### Overfitting on small datasets

Without enough data or augmentation, deep networks memorize training set.

### Confusing classification confidence with calibration

Softmax outputs aren't well-calibrated probabilities by default.

## Practical workflow

1. Define the task precisely
2. Gather and label data (often the hardest part)
3. Start with a pretrained model
4. Fine-tune with appropriate data augmentation
5. Evaluate on a held-out test set
6. Iterate on data, not just model
7. Profile for deployment constraints

The model is rarely the bottleneck. Data quality and quantity usually matter more.

## Where CV is going

- Foundation models (CLIP, SAM) for general-purpose visual understanding
- Multimodal models (text + image)
- Video understanding (longer context)
- 3D understanding from 2D images
- Edge deployment improvements

## Further Reading

- [TransformerArchitecture](TransformerArchitecture) — ViT foundation
- [MlModelDeployment](MlModelDeployment) — Getting models to production
- [InferenceServing](InferenceServing) — Serving infrastructure
- [ML Hub](MLHub) — Cluster index
