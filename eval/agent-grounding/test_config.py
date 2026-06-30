import config

def test_parse_mcp_key_takes_first_token():
    assert config.parse_mcp_key("abc123, def456 ") == "abc123"

def test_parse_mcp_key_single():
    assert config.parse_mcp_key("solo") == "solo"

def test_load_config_defaults_ollama(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1,k2")
    cfg = config.load_config([])
    assert cfg.provider == "ollama"
    assert cfg.base_url == "https://wiki.wikantik.com"
    assert cfg.ollama_base_url == "http://inference.jakefear.com:11434"
    assert cfg.mcp_key == "k1"
    assert cfg.agent_model == "gemma4:12b"
    assert cfg.judge_model == "gemma4:12b"
    assert cfg.lexical is False
    assert cfg.max_tool_iters == 6

def test_ollama_provider_does_not_require_anthropic_key(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config([])  # provider defaults to ollama
    assert cfg.anthropic_key is None

def test_anthropic_provider_requires_key(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    try:
        config.load_config(["--provider", "anthropic"])
        assert False, "should have raised"
    except SystemExit:
        pass

def test_anthropic_provider_defaults_to_sonnet(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config(["--provider", "anthropic"])
    assert cfg.provider == "anthropic"
    assert cfg.agent_model == "claude-sonnet-4-6"
    assert cfg.judge_model == "claude-sonnet-4-6"
    assert cfg.anthropic_key == "sk-test"

def test_missing_mcp_key_always_exits(monkeypatch):
    monkeypatch.delenv("MCP_ACCESS_KEYS", raising=False)
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    try:
        config.load_config([])
        assert False, "should have raised"
    except SystemExit:
        pass

def test_load_config_flags(monkeypatch):
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config(["--base-url", "http://localhost:8080", "--lexical",
                              "--agent-model", "gemma4:26b",
                              "--ollama-base-url", "http://host:9999/"])
    assert cfg.base_url == "http://localhost:8080"
    assert cfg.lexical is True
    assert cfg.agent_model == "gemma4:26b"
    assert cfg.ollama_base_url == "http://host:9999"

def test_load_config_samples_default(monkeypatch):
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config([])
    assert cfg.samples == 1

def test_load_config_samples_set(monkeypatch):
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config(["--samples", "3"])
    assert cfg.samples == 3
