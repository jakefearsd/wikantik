import config

def test_parse_mcp_key_takes_first_token():
    assert config.parse_mcp_key("abc123, def456 ") == "abc123"

def test_parse_mcp_key_single():
    assert config.parse_mcp_key("solo") == "solo"

def test_load_config_defaults(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1,k2")
    cfg = config.load_config([])
    assert cfg.base_url == "https://wiki.wikantik.com"
    assert cfg.anthropic_key == "sk-test"
    assert cfg.mcp_key == "k1"
    assert cfg.agent_model == "claude-sonnet-4-6"
    assert cfg.lexical is False
    assert cfg.max_tool_iters == 6

def test_load_config_flags(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config(["--base-url", "http://localhost:8080", "--lexical",
                              "--agent-model", "claude-opus-4-8"])
    assert cfg.base_url == "http://localhost:8080"
    assert cfg.lexical is True
    assert cfg.agent_model == "claude-opus-4-8"

def test_load_config_missing_anthropic_key(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    try:
        config.load_config([])
        assert False, "should have raised"
    except SystemExit:
        pass

def test_load_config_samples_default(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config([])
    assert cfg.samples == 1

def test_load_config_samples_set(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("MCP_ACCESS_KEYS", "k1")
    cfg = config.load_config(["--samples", "3"])
    assert cfg.samples == 3
