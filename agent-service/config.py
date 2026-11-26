"""IDE defaults. Environment variables override these values."""
import os

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://shopwise:shopwise@127.0.0.1:15439/shopwise")
COMMERCE_URL = os.getenv("COMMERCE_URL", "http://127.0.0.1:18081")
SERVICE_KEY = os.getenv("SERVICE_KEY", "local-shopwise-agent")
HOST = os.getenv("AGENT_HOST", "127.0.0.1")
PORT = int(os.getenv("AGENT_PORT", "18082"))
API_KEY = os.getenv("DASHSCOPE_API_KEY") or os.getenv("QWEN_API_KEY")
MODEL = os.getenv("QWEN_MODEL", "qwen-plus")
MODEL_URL = os.getenv("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
MODE = os.getenv("AGENT_MODE", "auto")
HTTP_TIMEOUT = float(os.getenv("AGENT_TOOL_TIMEOUT", "8"))
SHOPIFY_ENABLED = os.getenv("SHOPIFY_ENABLED", "false").lower() == "true"
SHOPIFY_MCP_URL = os.getenv("SHOPIFY_MCP_URL", "https://catalog.shopify.com/api/ucp/mcp")
SHOPIFY_AGENT_PROFILE = os.getenv("SHOPIFY_AGENT_PROFILE", "https://shopify.dev/ucp/agent-profiles/examples/2026-08-25/valid-with-capabilities.json")
