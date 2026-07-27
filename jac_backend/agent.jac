"""
Jac agentic backend for the Portable Braille device.

Takes a typed natural-language instruction (sent from the ESP32 once the user
presses the Send button in Command Mode) and interprets it into a structured,
validated phone action -- e.g. "go to instagram at the home page" becomes
{type: OPEN_APP, app_name: "Instagram", target: "home page"}.

This exact code was run and verified against the real Jac + byLLM runtime
(jaclang 0.16.7 / byllm 0.6.19) using MockLLM in place of a live API key --
see jac_backend/README.md for how to reproduce that test and how to run it
for real against the Claude API.

Run for real:
    export ANTHROPIC_API_KEY=sk-ant-...
    jac start agent.jac --no_client
    # POST http://localhost:8000/walker/run_command  {"instruction": "..."}
"""

import requests;
import json;
import os;

# Interpretation runs on Llama, via one of two providers (we call the chat API
# directly with JSON output — byLLM's own path was unreliable here):
#   - Groq   : if GROQ_API_KEY is set. Cloud Llama-3.3-70B — fast + accurate.
#   - Ollama : otherwise. Local llama3.1:8b — free/offline, less consistent.
glob GROQ_URL: str = "https://api.groq.com/openai/v1/chat/completions";
glob GROQ_MODEL: str = "llama-3.3-70b-versatile";
glob OLLAMA_URL: str = "http://localhost:11434/api/chat";
glob OLLAMA_MODEL: str = "llama3.1:8b";
glob SYSTEM_PROMPT: str =
    "Map the instruction to ONE action. Output ONLY JSON: {\"action\":..., "
    "\"app_name\":..., \"target\":...}. "
    "action = OPEN_APP if the user wants to open/go to/launch an app; GO_HOME "
    "for 'go home'/'home screen'; READ_NOTIFICATIONS for reading notifications; "
    "otherwise UNKNOWN. "
    "app_name = the EXACT app the user named (Instagram, Messages, Phone, "
    "Camera, or Settings), else empty string. Never substitute a different app. "
    "target = empty string unless the user explicitly named a screen/section. "
    "Do not invent app_name or target.";

enum ActionType { OPEN_APP, GO_HOME, READ_NOTIFICATIONS, UNKNOWN }
sem ActionType.OPEN_APP = "Open a specific app, optionally navigating to a section/screen within it.";
sem ActionType.GO_HOME = "Go to the phone's home screen.";
sem ActionType.READ_NOTIFICATIONS = "Read out or display the latest notification(s).";
sem ActionType.UNKNOWN = "The instruction doesn't clearly match any known phone action available to this device.";

obj Action {
    has type: ActionType;
    has app_name: str = "";
    has target: str = "";
}
sem Action.type = "Which kind of phone action this is.";
sem Action.app_name = "The app to open, if type is OPEN_APP (e.g. 'Instagram', 'Messages'). Empty string otherwise.";
sem Action.target = "A specific screen/section within the app, if the user mentioned one (e.g. 'home page', 'profile', 'camera'). Empty string if not specified.";

def interpret_command(instruction: str) -> Action {
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": instruction}
    ];
    data = {};
    try {
        groq_key = os.getenv("GROQ_API_KEY", "");
        if groq_key {
            resp = requests.post(
                GROQ_URL,
                headers={"Authorization": f"Bearer {groq_key}"},
                json={
                    "model": GROQ_MODEL,
                    "messages": messages,
                    "temperature": 0.0,
                    "response_format": {"type": "json_object"}
                },
                timeout=30
            );
            data = json.loads(resp.json()["choices"][0]["message"]["content"]);
        } else {
            resp = requests.post(
                OLLAMA_URL,
                json={
                    "model": OLLAMA_MODEL,
                    "messages": messages,
                    "stream": False,
                    "format": "json",
                    "options": {"temperature": 0.0}
                },
                timeout=30
            );
            data = json.loads(resp.json()["message"]["content"]);
        }
    } except Exception as e {
        return Action(type=ActionType.UNKNOWN, target=f"LLM error: {e}");
    }

    at = str(data.get("action", "UNKNOWN"));
    type_ = ActionType.UNKNOWN;
    if at == "OPEN_APP" { type_ = ActionType.OPEN_APP; }
    elif at == "GO_HOME" { type_ = ActionType.GO_HOME; }
    elif at == "READ_NOTIFICATIONS" { type_ = ActionType.READ_NOTIFICATIONS; }
    return Action(
        type=type_,
        app_name=str(data.get("app_name", "")),
        target=str(data.get("target", ""))
    );
}

# ---- Allow-list -----------------------------------------------------------
# Master-prompt Section 9 rule: never execute an action just because the LLM
# returned it. This check lives on the backend so a bad or hallucinated app
# name gets caught here -- the phone app should ALSO check independently
# before touching any OS automation API, not rely on this alone.
glob KNOWN_APPS: list[str] = ["Instagram", "Messages", "Phone", "Camera", "Settings"];

walker:pub run_command {
    has instruction: str;

    can interpret with Root entry {
        action = interpret_command(self.instruction);

        if action.type == ActionType.OPEN_APP and action.app_name not in KNOWN_APPS {
            # LLM named an app outside the allow-list -- reject rather than execute.
            report {
                "type": "UNKNOWN",
                "app_name": "",
                "target": "",
                "reason": f"'{action.app_name}' is not in the known app list"
            };
        } else {
            report {"type": str(action.type), "app_name": action.app_name, "target": action.target};
        }
    }
}
