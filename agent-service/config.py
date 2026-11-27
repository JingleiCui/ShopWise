"""IDE defaults. Environment variables override these values."""
import os

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://shopwise:shopwise@127.0.0.1:15439/shopwise")
COMMERCE_URL = os.getenv("COMMERCE_URL", "http://127.0.0.1:18081")
COMMERCE_HOSTS = {host.strip() for host in os.getenv("COMMERCE_HOSTS","localhost,127.0.0.1,::1,commerce-api").split(",")}
SERVICE_KEY = os.getenv("SERVICE_KEY", "local-shopwise-agent")
HOST = os.getenv("AGENT_HOST", "127.0.0.1")
PORT = int(os.getenv("AGENT_PORT", "18082"))
API_KEY = os.getenv("DASHSCOPE_API_KEY") or os.getenv("QWEN_API_KEY")
MODEL = os.getenv("QWEN_MODEL", "qwen-plus")
MODEL_URL = os.getenv("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
MODE = os.getenv("AGENT_MODE", "auto")
if os.getenv("SHOPWISE_ENV") == "prod":
    if len(SERVICE_KEY) < 32 or not os.getenv("DATABASE_URL"):
        raise RuntimeError("Production requires a non-default service key and checkpoint database")

HTTP_TIMEOUT = float(os.getenv("AGENT_TOOL_TIMEOUT", "8"))
