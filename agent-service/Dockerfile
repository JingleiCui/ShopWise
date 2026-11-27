FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.11.5 /uv /usr/local/bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev && chmod -R a+rX /app
COPY *.py ./
ENV AGENT_HOST=0.0.0.0 SHOPWISE_ENV=prod PYTHONUNBUFFERED=1
USER 65532:65532
EXPOSE 18082
CMD ["/app/.venv/bin/python","main.py"]
