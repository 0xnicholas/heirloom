# Heirloom AI Gateway

A lightweight, high-performance AI Gateway written in Rust.

## Quick Start

```bash
# Build
cargo build --release

# Run with config
HEIRLOOM_CONFIG_PATH=config.toml ./target/release/heirloom
```

## Configuration

See `config.example.toml` for configuration options.

## API

- `POST /v1/chat/completions` - Chat completion
- `POST /v1/embeddings` - Text embedding
- `GET /v1/models` - List available models
- `GET /health` - Health check

## Docker

```bash
docker build -t heirloom .
docker run -p 8080:8080 -v $(pwd)/config.toml:/etc/heirloom/config.toml heirloom
```
