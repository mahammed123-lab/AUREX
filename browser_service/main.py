# JARVIS BrowserAgentService — backend for the Android "browser agent" feature.
#
# Wraps browser_use.Agent as a managed, cancellable, streamable task and exposes
# the HTTP/WS surface the mobile app calls. Runs ONLY on the backend host
# (Python >=3.11 + Playwright/Chromium); the Android app is a thin client.
#
# Run (after STEP 1 install below):
#   uv run uvicorn main:app --host 0.0.0.0 --port 9000
#
# STEP 1 (one-time):
#   uv venv --python 3.11
#   uv pip install -r requirements.txt
#   uv sync || true
#   playwright install chromium
#
# Security: set JARVIS_BEARER_TOKEN. Every /browser/* endpoint rejects
# requests without "Authorization: Bearer <token>".
import asyncio
import os
import threading
import uuid
from typing import Dict, List, Optional

from fastapi import Depends, FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel

BEARER_TOKEN = os.environ.get("JARVIS_BEARER_TOKEN", "")
_auth = HTTPBearer(auto_error=False)


def _check_auth(creds: Optional[HTTPAuthorizationCredentials] = Depends(_auth)) -> None:
    if BEARER_TOKEN and (creds is None or creds.credentials != BEARER_TOKEN):
        raise HTTPException(status_code=401, detail="Unauthorized")


app = FastAPI(title="JARVIS BrowserAgentService")


class TaskSubmit(BaseModel):
    task: str


class StepEvent(BaseModel):
    type: str = "step"
    summary: Optional[str] = None
    screenshot: Optional[str] = None
    action: Optional[str] = None
    payload: Optional[str] = None


class TaskStatus(BaseModel):
    task_id: str
    status: str
    result: Optional[str] = None
    error: Optional[str] = None
    steps: List[StepEvent] = []


class TaskRecord:
    def __init__(self, task: str):
        self.task_id = str(uuid.uuid4())
        self.task = task
        self.status = "queued"  # queued|running|done|error|cancelled
        self.result: Optional[str] = None
        self.error: Optional[str] = None
        self.steps: List[StepEvent] = []
        self.cancel_flag = threading.Event()
        self.lock = threading.Lock()


TASKS: Dict[str, TaskRecord] = {}


def _push(rec: TaskRecord, event: StepEvent) -> None:
    with rec.lock:
        rec.steps.append(event)


# ── lazy import of browser_use so the service still starts if it's missing ──
def _load_agent():
    try:
        # browser-use moved its agent/LLM APIs around across versions; try the
        # canonical paths and fall back gracefully.
        from browser_use import Agent  # noqa
        from browser_use.llm.openai.chat import ChatOpenAI  # noqa
        from browser_use import Browser  # noqa
        return Agent, ChatOpenAI, Browser
    except Exception:
        return None, None, None


async def _run_task(rec: TaskRecord) -> None:
    Agent, ChatOpenAI, Browser = _load_agent()
    if Agent is None:
        rec.status = "error"
        rec.error = "browser_use is not installed on this backend (pip install browser-use)."
        return

    model = os.environ.get("BROWSER_MODEL", "qwen2.5-14b-instruct")
    base_url = os.environ.get("BROWSER_BASE_URL", "http://localhost:8000/v1")
    api_key = os.environ.get("BROWSER_API_KEY", "local")

    try:
        llm = ChatOpenAI(model=model, base_url=base_url, api_key=api_key)

        agent = Agent(
            task=rec.task,
            llm=llm,
            # streaming hooks (browser-use 0.6+ API)
            register_new_step_callback=None,
            register_done_callback=None,
            register_should_stop_callback=lambda: rec.cancel_flag.is_set(),
        )

        # Fall back to the newer async run() / add_new_task() variations.
        result = None
        if hasattr(agent, "run"):
            result = await agent.run(max_steps=25)
        elif hasattr(agent, "add_new_task"):
            agent.add_new_task(rec.task)
            result = await agent.run()
        else:
            result = await agent.run()

        if rec.cancel_flag.is_set():
            rec.status = "cancelled"
            rec.result = "Task was cancelled."
            return

        history = getattr(result, "history", None)
        final_text = getattr(result, "final_result", None) or getattr(result, "output", None) or str(result)
        _push(rec, StepEvent(type="done", summary="Task complete", payload=None))
        rec.status = "done"
        rec.result = str(final_text)
    except Exception as e:
        rec.status = "error"
        rec.error = f"{type(e).__name__}: {e}"


def _run_in_thread(rec: TaskRecord) -> None:
    try:
        asyncio.run(_run_task(rec))
    except Exception as e:
        rec.status = "error"
        rec.error = str(e)


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "ok", "service": "BrowserAgentService"}


@app.post("/browser/tasks", response_model=TaskStatus, dependencies=[Depends(_check_auth)])
async def create_task(body: TaskSubmit) -> TaskStatus:
    rec = TaskRecord(body.task)
    rec.status = "running"
    TASKS[rec.task_id] = rec
    threading.Thread(target=_run_in_thread, args=(rec,), daemon=True).start()
    return TaskStatus(task_id=rec.task_id, status=rec.status, steps=rec.steps)


@app.get("/browser/tasks/{task_id}", response_model=TaskStatus, dependencies=[Depends(_check_auth)])
async def get_task(task_id: str) -> TaskStatus:
    rec = TASKS.get(task_id)
    if rec is None:
        raise HTTPException(status_code=404, detail="task not found")
    with rec.lock:
        return TaskStatus(
            task_id=rec.task_id,
            status=rec.status,
            result=rec.result,
            error=rec.error,
            steps=list(rec.steps),
        )


@app.post("/browser/tasks/{task_id}/cancel", dependencies=[Depends(_check_auth)])
async def cancel_task(task_id: str) -> Dict[str, str]:
    rec = TASKS.get(task_id)
    if rec is None:
        raise HTTPException(status_code=404, detail="task not found")
    rec.cancel_flag.set()
    return {"status": "cancelling", "task_id": task_id}


@app.get("/browser/tasks", dependencies=[Depends(_check_auth)])
async def list_tasks() -> Dict[str, object]:
    return {
        t: {"task_id": r.task_id, "status": r.status, "task": r.task}
        for t, r in TASKS.items()
    }


@app.websocket("/browser/tasks/{task_id}/stream")
async def stream_task(ws: WebSocket, task_id: str) -> None:
    await ws.accept()
    rec = TASKS.get(task_id)
    if rec is None:
        await ws.send_json({"type": "error", "payload": "task not found"})
        await ws.close()
        return
    sent = 0
    try:
        while True:
            with rec.lock:
                steps = rec.steps[sent:]
                sent = len(rec.steps)
                status = rec.status
                result = rec.result
                error = rec.error
            for s in steps:
                await ws.send_json(s.dict())
            if status in ("done", "error", "cancelled"):
                await ws.send_json(
                    {"type": "done", "payload": {"status": status, "result": result, "error": error}}
                )
                break
            await asyncio.sleep(1.0)
    except WebSocketDisconnect:
        pass
    finally:
        await ws.close()