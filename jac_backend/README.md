# Portable Braille — agentic command backend

Takes a typed instruction (e.g. "go to instagram at the home page") and returns a structured, validated phone action for the companion app to execute.

## What's actually been verified

I installed `jaclang` (0.16.7) and `byllm` (0.6.19) and ran this code for real — not just written from memory:

- `interpret_command`'s `by llm()` + typed `Action` return, with `MockLLM` standing in for a live API key, correctly returns structured output (`ActionType.OPEN_APP`, `app_name="Instagram"`, `target="home page"`) for the exact example instruction.
- The allow-list rejection logic (`action.app_name not in KNOWN_APPS`) correctly flags an out-of-list app as `UNKNOWN`.
- `run_command` served as a real REST endpoint via `jac start agent.jac --no_client`, and `curl -X POST http://localhost:8000/walker/run_command -d '{"instruction": "go to instagram at the home page"}'` returned the correct JSON.
- `agent.test.jac` passes via `jac test`.

**What's not verified:** the actual call to the real Claude API (no key was available in the environment I built this in) — only the mocked path. The plumbing is proven; the real model call just needs a key.

## Setup

```bash
pip install jaclang byllm --break-system-packages   # or without --break-system-packages in a venv
export ANTHROPIC_API_KEY=sk-ant-...
```

## Run it for real

```bash
jac start agent.jac --no_client
```

```bash
curl -X POST http://localhost:8000/walker/run_command \
  -H "Content-Type: application/json" \
  -d '{"instruction": "go to instagram at the home page"}'
```

Expected response shape:
```json
{
  "ok": true,
  "data": {
    "reports": [
      {"type": "ActionType.OPEN_APP", "app_name": "Instagram", "target": "home page"}
    ]
  }
}
```

## Test without an API key

```bash
jac test agent.test.jac
```

Runs against `MockLLM` — validates the code structure and typed output without hitting a real model or costing tokens.

## Extending the action set

Right now `ActionType` has 4 members (`OPEN_APP`, `GO_HOME`, `READ_NOTIFICATIONS`, `UNKNOWN`) — deliberately small, per the master prompt's "keep the initial action set small" guidance (Section 9). To add a new action:

1. Add a member to `ActionType` with a `sem` description — this is literally the prompt the LLM sees, so be specific.
2. If it needs its own parameters beyond `app_name`/`target`, add fields to `Action`.
3. If it's an app-launch action, add the app to `KNOWN_APPS` or it'll get rejected by the allow-list.

## Wiring this to the phone app

This backend expects the phone app to:
1. Buffer the typed instruction while the device is in Command Mode (see master prompt Section 4/11).
2. `POST` it here on Send.
3. Check the returned `type` against its own allow-list *again* before calling any OS automation API — this backend's allow-list is a first line of defense, not a substitute for the phone app's own check (master prompt Section 9).
4. Map `OPEN_APP` + `app_name` (+ optional `target`) to the actual platform call (`Intent` on Android, `App Intents`/Shortcuts on iOS).
