# JARVIS BrowserAgentService

Backend for the AUREX/JARVIS Android "browser agent" feature. It wraps the
`browser-use` Python library (Chromium + LLM) as a managed, cancellable,
streamable task and exposes an HTTP/WebSocket API that the Android thin client
talks to.

> browser-use CANNOT run on-device (needs Python >=3.11 + Chromium). The
> Android app is a thin client: it submits a task and renders streamed
> status/screenshots.

## One-time setup

```bash
uv venv --python 3.11
uv pip install -r requirements.txt
playwright install chromium
```

## Configure (env vars)

| Var | Default | Purpose |
|---|---|---|
| `JARVIS_BEARER_TOKEN` | `""` (auth off) | Set this and the app must send `Authorization: Bearer <token>` |
| `BROWSER_MODEL` | `qwen2.5-14b-instruct` | Model name your OpenAI-compatible bridge serves |
| `BROWSER_BASE_URL` | `http://localhost:8000/v1` | JARVIS's OpenAI-compatible FastAPI bridge |
| `BROWSER_API_KEY` | `local` | Placeholder key your bridge expects |

If the local model underperforms on browsing, point `BROWSER_BASE_URL` at a
cloud provider the browser-use `ChatOpenAI` accepts (NVIDIA NIM, OpenRouter,
Anthropic gateway).

## Run

```bash
uv run uvicorn main:app --host 0.0.0.0 --port 9000
```

## API surface (mobile app contract)

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/browser/tasks` | `{ "task": string }` | `{ task_id }` |
| GET | `/browser/tasks/{task_id}` | — | `{ status, result?, error?, steps[] }` |
| WS | `/browser/tasks/{task_id}/stream` | — | step events `{type: "step"\|"screenshot"\|"done"\|"error", payload}` |
| POST | `/browser/tasks/{task_id}/cancel` | — | `{ status: "cancelling" }` |

All routes (except `/health`) require `Authorization: Bearer <JARVIS_BEARER_TOKEN>`
when the token is set.

## Android side

Set the backend URL in the app (`API Configuration → Custom Backend URL`) to
`http://<this-host>:9000`, then a command like *"browse <task>"* routes through
`startBrowserTask()` and streams progress into chat.

## Future AGI integration (observability)

Each Android request already sends a W3C `traceparent` header (see
`TraceparentInterceptor.kt`). To nest backend spans under it, run Future AGI's
collector and start your FastAPI/`browser-use` spans with that remote parent —
see the Future AGI Phase 2/4 docs. Do NOT put the gateway virtual key or provider
keys on-device.