---
cluster: agentic-ai
canonical_id: 01KQ0P44T9S7BYCJ5J13MWMYCT
title: Ollama Setup
type: article
tags:
- model
- ollama
- local
- llama3
- devops
summary: Technical guide for deploying Ollama in production, including vRAM requirements for Llama 3, systemd service configuration, and GPU pass-through.
status: active
date: '2026-04-24'
auto-generated: false
---
# Deploying Ollama: Local Inference Infrastructure

Ollama is the primary tool for running Large Language Models (LLMs) locally with minimal overhead. For production use, it requires careful hardware sizing and service-level management.

## Hardware Sizing: vRAM Requirements

The most critical factor in local inference is the available video RAM (vRAM). If a model does not fit in vRAM, it offloads to system RAM, which is significantly slower (often 10x-50x slower).

### Llama 3 vRAM Table (Approximate)

| Model Size | Quantization | vRAM Required | Recommended Hardware |
|---|---|---|---|
| **Llama 3 8B** | 4-bit (Q4_K_M) | ~5.5 GB | RTX 3060 (12GB) / Apple M1+ |
| **Llama 3 8B** | 8-bit (Q8_0) | ~9.0 GB | RTX 3080 (10GB+) / Apple M1+ |
| **Llama 3 70B** | 4-bit (Q4_K_M) | ~40.0 GB | 2x RTX 3090/4090 (48GB) / A6000 |
| **Llama 3 70B** | 8-bit (Q8_0) | ~72.0 GB | 2x A6000 / A100 / Mac Studio (128GB) |

**Note on Unified Memory:** Apple Silicon (Mac Studio/Pro) uses unified memory, meaning system RAM can be allocated to the GPU. For 70B models, a Mac with 64GB+ RAM is often the most cost-effective local solution.

## Production Deployment: systemd

On Linux, Ollama should run as a systemd service to ensure it restarts after crashes or reboots.

### Example Service File
Create `/etc/systemd/system/ollama.service`:

```ini
[Unit]
Description=Ollama Service
After=network-online.target

[Service]
ExecStart=/usr/local/bin/ollama serve
User=ollama
Group=ollama
Restart=always
RestartSec=3
Environment="OLLAMA_HOST=0.0.0.0"
Environment="OLLAMA_ORIGINS=*"

[Install]
WantedBy=default.target
```

**Concrete Command:** After creating the file, enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable ollama
sudo systemctl start ollama
```

## GPU Pass-through with Docker

To run Ollama inside Docker with GPU acceleration, you must install the **NVIDIA Container Toolkit**.

### Docker Compose Configuration
```yaml
services:
  ollama:
    image: ollama/ollama:latest
    container_name: ollama
    ports:
      - "11434:11434"
    volumes:
      - ./ollama_data:/root/.ollama
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu]
```

## Advanced Configuration: Model Customization

Use a `Modelfile` to bake system prompts and parameters into a custom model tag.

### Concrete Example: Creative Assistant
Create a file named `CreativeAssistant.Modelfile`:

```dockerfile
FROM llama3:8b
# Set creativity parameters
PARAMETER temperature 0.8
PARAMETER top_p 0.9
# Set the persona
SYSTEM """
You are a creative writing assistant. You favor vivid imagery and metaphor. 
Keep responses under 200 words unless asked otherwise.
"""
```

Then create the model:
```bash
ollama create creative-llama -f CreativeAssistant.Modelfile
ollama run creative-llama "Describe a cyberpunk city in the rain."
```

## Monitoring Performance

Use the `OLLAMA_DEBUG=1` environment variable to see detailed logging of which layers are being offloaded to the GPU. During inference, check GPU utilization with:
```bash
nvidia-smi -l 1
```
Look for **Volatile GPU-Util** and **Memory-Usage** to confirm the model is fully resident in vRAM.
