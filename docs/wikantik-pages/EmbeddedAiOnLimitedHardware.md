---
type: article
cluster: technology
status: active
summary: Running AI models on microcontrollers, phones, and edge devices — what works,
  what doesn't, proven use cases, and the techniques that make small models effective
  on constrained hardware
date: 2026-03-17T00:00:00Z
tags:
- ai
- embedded
- edge-computing
- machine-learning
- iot
- technology
related:
- TheFutureOfMachineLearning
- MachineLearning
- LlmsSinceTwentyTwenty
- RunningLocalLlms
- FoundationalAlgorithmsForComputerScientists
- DistributedComputingEvolution
---
# Embedded AI on Limited Hardware

The dominant narrative in AI focuses on scale: bigger models, bigger clusters, bigger budgets. But a parallel revolution is happening at the other end of the spectrum — running useful AI on devices with kilobytes of RAM, milliwatts of power, and no network connection. This is embedded AI, and it is already deployed at a scale that dwarfs cloud AI in sheer unit count.

There are roughly 250 billion microcontrollers in active use worldwide. Adding meaningful intelligence to even a fraction of them represents an enormous opportunity. The question is not whether AI can run on limited hardware — it already does — but which techniques work, which use cases are proven, and where the boundaries lie.

## The Hardware Landscape

Embedded AI targets a wide range of devices, each with different constraints:

| Class | Examples | RAM | Compute | Power | Connectivity |
|-------|----------|-----|---------|-------|--------------|
| **Microcontrollers (MCUs)** | ARM Cortex-M4/M7, ESP32, Nordic nRF | 256 KB–2 MB | 100–600 MHz, no GPU | µW–mW | BLE, LoRa, Wi-Fi |
| **Application processors** | Raspberry Pi, Jetson Nano, i.MX 8M | 1–8 GB | 1–2 GHz, optional GPU/NPU | 2–15 W | Full networking |
| **Mobile SoCs** | Apple A-series, Snapdragon 8 Gen 3 | 6–12 GB | Multi-core + Neural Engine/NPU | 1–5 W (AI workload) | Full networking |
| **Dedicated AI accelerators** | Google Coral (Edge TPU), Hailo-8, Intel Movidius | Varies | 2–26 TOPS | 1–5 W | USB, PCIe, M.2 |

The key insight: the constraint is not just compute — it is the intersection of compute, memory, power, latency, cost, and connectivity. A model that fits in 2 MB of flash and runs in 50 ms on a Cortex-M7 is fundamentally different from one that needs 4 GB of VRAM and a GPU.

## Why Run AI on the Edge?

Cloud inference works well for many applications. Running models locally matters when:

**Latency is critical.** A self-driving car cannot wait 200 ms for a cloud round-trip to decide whether to brake. An industrial quality inspector cannot pause the production line for network latency. Voice wake-word detection must respond in under 500 ms to feel responsive.

**Connectivity is unreliable or absent.** Agricultural sensors in remote fields, underwater drones, wildlife monitoring cameras, and factory floor equipment often operate with intermittent or no connectivity.

**Privacy is non-negotiable.** Medical devices processing patient data, home security cameras, and voice assistants that people use in private spaces all benefit from keeping data on-device. What never leaves the device cannot be intercepted or subpoenaed.

**Cost per inference matters at scale.** When you are deploying millions of devices, even $0.001 per cloud inference adds up. On-device inference has zero marginal cost after deployment.

**Power budget is constrained.** Battery-powered and energy-harvesting devices cannot afford the power consumption of continuous cloud communication.

## Proven Use Cases

Embedded AI is not theoretical. These applications are in production today, at scale:

### Keyword Spotting and Wake Words

"Hey Siri," "OK Google," and "Alexa" all run on-device. A small neural network (typically 50–500 KB) continuously listens for the wake word on a low-power DSP or neural accelerator, consuming under 1 mW. Only after detection does the device activate its main processor and cloud connection.

**Why it works on limited hardware:** The vocabulary is tiny (one or a few phrases), the audio window is short (1–2 seconds), and the model architecture (depthwise separable convolutions or small transformers) is well-optimized for this task.

**Accuracy:** Commercial wake-word detectors achieve >95% true positive rate with <0.5 false activations per day in typical environments.

### Predictive Maintenance

Vibration sensors on industrial motors, pumps, and turbines run tiny [machine learning](MachineLearning) models that detect anomalous patterns indicating impending failure. The model runs on the sensor's MCU, sending alerts only when anomalies are detected — reducing data transmission by 99% compared to streaming raw sensor data.

**Why it works:** The input is low-dimensional (accelerometer time series), the classification task is binary or few-class (normal vs. several failure modes), and the model can be a small random forest or 1D CNN under 100 KB.

**Proven results:** Deployed by Siemens, SKF, and others. Typical systems detect bearing failures 2–4 weeks before catastrophic failure with >90% precision.

### Visual Inspection and Defect Detection

Factory cameras running MobileNet or EfficientNet variants detect product defects in real-time on the production line. Models run on dedicated AI accelerators (Google Coral, Hailo) or capable MCUs with camera interfaces.

**Why it works:** The defect categories are well-defined and domain-specific, the images are taken under controlled lighting and angles, and transfer learning from ImageNet provides a strong starting point. A MobileNetV2 model (3.4M parameters, ~14 MB) classifies 224×224 images in under 10 ms on a Coral Edge TPU.

**Proven results:** Deployed in semiconductor fabrication, food processing, automotive assembly, and textile manufacturing. Accuracy routinely exceeds 99% for well-defined defect types, often outperforming human inspectors who fatigue over long shifts.

### Person Detection and Counting

Security cameras, smart doorbells, and occupancy sensors run person detection models on-device. This enables real-time alerts without streaming video to the cloud, which reduces bandwidth costs and addresses privacy concerns.

**Why it works:** Person detection is a well-solved problem in computer vision. Models like MobileNet-SSD and YOLO-Nano can run at 10–30 FPS on mobile NPUs or dedicated accelerators. Quantized to INT8, these models fit in 2–8 MB.

### Voice Command Recognition

Beyond wake words, small models handle limited-vocabulary voice commands entirely on-device: "lights on," "set timer," "next track." Consumer devices from headphones to kitchen appliances use this approach.

**Why it works:** The vocabulary is typically 10–50 commands. A model handling this fits in 200–500 KB and runs in under 100 ms on a Cortex-M4.

### Gesture Recognition

Smartphones, wearables, and automotive interfaces use accelerometer and gyroscope data to recognize gestures — wrist flicks, air writing, steering wheel taps. Models are typically tiny LSTMs or 1D CNNs running on the device's main processor or motion coprocessor.

### Anomaly Detection in Network Traffic

Edge routers and IoT gateways run lightweight anomaly detection models to identify suspicious network patterns — port scans, unusual data exfiltration, command-and-control beaconing — without sending all traffic to a central SIEM.

### Smart Agriculture

Soil sensors with embedded ML classify soil moisture patterns to optimize irrigation scheduling. Camera traps use on-device species classification to save battery by only recording when target species are detected. Drone-mounted models perform real-time crop health assessment.

## Techniques That Make It Work

### Quantization

The single most impactful technique for embedded deployment. Quantization reduces model weights and activations from 32-bit floating point to 8-bit integer (INT8) or even lower precision:

| Precision | Size Reduction | Speed Gain | Typical Accuracy Impact |
|-----------|---------------|------------|------------------------|
| FP32 → FP16 | 2x | 1.5–2x | Negligible |
| FP32 → INT8 | 4x | 2–4x | < 1% accuracy loss |
| FP32 → INT4 | 8x | 3–6x | 1–3% accuracy loss |
| FP32 → Binary | 32x | 10–50x | Significant, task-dependent |

Post-training quantization (applying quantization after training) is simplest but may lose accuracy. Quantization-aware training (simulating quantization during training) recovers most of the lost accuracy.

TensorFlow Lite, ONNX Runtime, and vendor-specific toolchains (ARM NN, Apple Core ML, Qualcomm SNPE) all support INT8 quantization with minimal developer effort.

### Pruning

Removing weights, neurons, or entire layers that contribute little to model accuracy. Structured pruning (removing whole filters or attention heads) is more hardware-friendly than unstructured pruning (zeroing individual weights), because it reduces actual computation rather than just creating sparse matrices.

A well-pruned model can be 5–10x smaller with less than 1% accuracy degradation. Combined with quantization, the compounding effect is dramatic.

### Knowledge Distillation

Training a small "student" model to mimic the outputs of a large "teacher" model. The student learns not just the correct labels but the teacher's confidence distribution across all classes, which contains richer information. Distillation routinely produces students that outperform equivalently-sized models trained from scratch.

This is how many production embedded models are created: train the best possible large model, then distill it down to the target hardware's constraints.

### Efficient Architectures

Purpose-built architectures for constrained devices:

- **MobileNet (v1, v2, v3):** Uses depthwise separable convolutions to reduce computation by 8–9x compared to standard convolutions. MobileNetV2's inverted residuals are widely used in mobile and embedded vision.
- **EfficientNet:** Compound scaling of depth, width, and resolution. EfficientNet-Lite variants are optimized for mobile deployment.
- **SqueezeNet:** Achieves AlexNet-level accuracy in under 0.5 MB through aggressive use of 1×1 convolutions.
- **TinyBERT / DistilBERT:** Compressed language models for on-device NLP tasks like sentiment analysis and intent classification.
- **Phi-3 Mini / Gemma 2B:** Small language models (2–3.8B parameters) that run on phones and laptops for basic text generation and understanding.

### TinyML: ML on Microcontrollers

TinyML pushes ML onto devices with as little as 256 KB of RAM and 1 MB of flash. The key framework is TensorFlow Lite Micro, which provides a minimal C++ runtime with no dynamic memory allocation and no operating system dependency.

Typical TinyML models:
- Keyword spotting: ~50 KB model, 20 KB RAM, runs on Cortex-M4
- Accelerometer gesture recognition: ~20 KB model, 10 KB RAM
- Simple image classification: ~300 KB model, 100 KB RAM, requires Cortex-M7 or better

The constraints force creative solutions: fixed-point arithmetic, lookup tables instead of transcendental functions, and hand-optimized CMSIS-NN kernels for ARM processors.

## Frameworks and Tools

| Tool | Target | Key Feature |
|------|--------|-------------|
| **TensorFlow Lite / Lite Micro** | Mobile, MCU | Broadest hardware support, strong quantization tools |
| **ONNX Runtime** | Mobile, edge | Cross-framework model format, optimization passes |
| **Apple Core ML** | iOS/macOS | Deep integration with Apple Neural Engine |
| **Qualcomm AI Engine / SNPE** | Snapdragon SoCs | Hexagon DSP and NPU acceleration |
| **ARM NN** | ARM Cortex | Optimized for ARM CPU and Ethos NPU |
| **NVIDIA TensorRT** | Jetson, datacenter GPUs | Aggressive layer fusion and kernel optimization |
| **Edge Impulse** | MCUs, edge | End-to-end platform from data collection to deployment |
| **Apache TVM** | Any hardware | Compiler-based optimization for diverse targets |

## What Does Not Work (Yet)

Honesty about limitations is as important as enthusiasm about capabilities:

**General-purpose language generation on MCUs.** Even the smallest useful LLMs (1–3B parameters) require gigabytes of RAM. Microcontrollers cannot run them. Small language models on phones and Raspberry Pi-class devices work for simple tasks but cannot match cloud model quality.

**Complex scene understanding on low-power devices.** Detecting a person in a frame works well. Understanding the semantic relationship between multiple objects, predicting intentions, or answering open-ended questions about a scene requires models too large for MCU-class hardware.

**Training on-device.** On-device training (not just inference) remains extremely limited. Federated learning performs gradient computation on-device, but even this requires application-processor-class hardware. MCUs run inference only.

**Reliable speech recognition with large vocabularies.** On-device speech recognition with 50,000+ word vocabularies requires application processors with several GB of RAM. MCU-class devices handle only small command vocabularies.

## The Economics of Embedded AI

The cost equation for embedded AI differs fundamentally from cloud AI:

- **BOM cost matters.** Adding a $2 AI accelerator chip to a $15 device is a 13% cost increase. The AI capability must justify that cost in the product's value proposition.
- **Power consumption is a feature.** A model that runs on 1 mW enables a battery-powered sensor that lasts 5 years. A model requiring 5 W needs wall power or frequent recharging.
- **Deployment is one-way.** Unlike cloud models that can be updated hourly, embedded firmware updates are expensive, risky, and infrequent. The model must work reliably from day one.
- **Edge cases are safety-critical.** A cloud chatbot that occasionally hallucinates is annoying. An embedded model in a medical device or industrial controller that misclassifies is dangerous.

## Getting Started

For practitioners coming from cloud ML who want to explore embedded AI:

1. **Start with TensorFlow Lite on a phone or Raspberry Pi.** Convert an existing model, measure latency and accuracy, experiment with quantization. This requires no new hardware.
2. **Try Edge Impulse with a $10 Arduino Nano 33 BLE Sense.** The platform walks you through data collection, training, and deployment for accelerometer and audio classification.
3. **Profile your target hardware.** Measure actual inference latency, memory usage, and power consumption — don't rely on theoretical TOPS numbers, which rarely reflect real workloads.
4. **Design for the constraint, not against it.** The best embedded AI solutions work with hardware limitations rather than fighting them — smaller models, simpler tasks, clever preprocessing.

For more on running models locally (including on desktop and laptop hardware), see [Running Local LLMs](RunningLocalLlms). For the broader trajectory of on-device ML, see [The Future of Machine Learning](TheFutureOfMachineLearning).

## Further Reading

- [Machine Learning](MachineLearning) — Core ML concepts and the training pipeline
- [The Future of Machine Learning](TheFutureOfMachineLearning) — Edge AI trends and what's coming next
- [LLMs Since 2020](LlmsSinceTwentyTwenty) — The large model landscape that embedded AI complements
- [Running Local LLMs](RunningLocalLlms) — Running models on personal hardware
- [Foundational Algorithms for Computer Scientists](FoundationalAlgorithmsForComputerScientists) — The algorithmic foundations behind efficient implementations
