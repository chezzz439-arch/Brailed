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

# ---------------------------------------------------------------------------
# Object-Spatial capability graph (master prompt Section 9).
#
# Each phone capability is a NODE with its own ability. The run_command WALKER
# interprets the instruction into an Action, then traverses from root to the
# capability node that handles that action type. The node validates the action
# against its own rules before producing a result -- so the LLM never gets to
# execute an action merely by naming it; a capability node has to accept it.
# ---------------------------------------------------------------------------

glob KNOWN_APPS: list[str] = ["Instagram", "Messages", "Phone", "Camera", "Settings"];

# App-name aliases the model might emit -> canonical app in KNOWN_APPS.
glob APP_ALIASES: dict[str, str] = {
    "insta": "Instagram", "ig": "Instagram", "the gram": "Instagram",
    "message": "Messages", "messaging": "Messages", "texts": "Messages", "sms": "Messages",
    "dialer": "Phone", "call": "Phone", "calls": "Phone", "caller": "Phone",
    "cam": "Camera", "photo": "Camera",
    "setting": "Settings", "config": "Settings", "preferences": "Settings"
};

# Resolve a possibly-messy app name to a known app, else empty string.
def canonical_app(name: str) -> str {
    n = name.strip().lower();
    if not n { return ""; }
    for app in KNOWN_APPS {
        if n == app.lower() { return app; }
    }
    if n in APP_ALIASES { return APP_ALIASES[n]; }
    for app in KNOWN_APPS {
        if app.lower() in n { return app; }
    }
    for alias in APP_ALIASES.keys() {
        if alias in n { return APP_ALIASES[alias]; }
    }
    return "";
}

def make_result(type_: str, app: str, target: str, reason: str) -> dict {
    return {"type": type_, "app_name": app, "target": target, "reason": reason};
}

# ---- Capability nodes -----------------------------------------------------
# One node per phone capability. The router walker visits them; each walker
# ability below is scoped to a node TYPE, so it only fires on its own node.

node Capability {
    has action_type: str;   # the ActionType this node answers for (metadata)
    has label: str = "";
}

node AppLauncher(Capability) { has allowed: list[str] = []; }
node HomeNav(Capability) {}
node Notifications(Capability) {}
node Unknown(Capability) {}

# ---- Session state (persisted on the root) --------------------------------

node Session {
    has count: int = 0;
    has history: list[dict] = [];
}

# ---- Router walker --------------------------------------------------------
# Interprets the instruction, then traverses root -> capability nodes. Each
# capability decides for itself whether it accepts the action (defense: the
# LLM can't execute anything a node doesn't explicitly allow).

walker:pub run_command {
    has instruction: str;
    has decided: Action = Action(type=ActionType.UNKNOWN);
    has answer: dict = {};

    can route with Root entry {
        # Build the capability graph + session once, lazily, on this root.
        if not [-->] {
            here ++> AppLauncher(action_type="OPEN_APP", label="apps", allowed=KNOWN_APPS);
            here ++> HomeNav(action_type="GO_HOME", label="home");
            here ++> Notifications(action_type="READ_NOTIFICATIONS", label="notifications");
            here ++> Unknown(action_type="UNKNOWN", label="fallback");
            here ++> Session();
        }
        self.decided = interpret_command(self.instruction);
        visit [-->];
    }

    can at_apps with AppLauncher entry {
        if self.decided.type == ActionType.OPEN_APP {
            app = canonical_app(self.decided.app_name);
            if app and (app in here.allowed) {
                self.answer = make_result("OPEN_APP", app, self.decided.target, "");
            } else {
                self.answer = make_result(
                    "UNKNOWN", "", "", f"'{self.decided.app_name}' is not an app I can open"
                );
            }
        }
    }

    can at_home with HomeNav entry {
        if self.decided.type == ActionType.GO_HOME {
            self.answer = make_result("GO_HOME", "", "", "");
        }
    }

    can at_notifications with Notifications entry {
        if self.decided.type == ActionType.READ_NOTIFICATIONS {
            self.answer = make_result("READ_NOTIFICATIONS", "", "", "");
        }
    }

    can at_unknown with Unknown entry {
        if self.decided.type == ActionType.UNKNOWN {
            why = self.decided.target if self.decided.target else "I can't do that yet";
            self.answer = make_result("UNKNOWN", "", "", why);
        }
    }

    can at_session with Session entry {
        here.count += 1;
        if self.answer { here.history = (here.history + [self.answer])[-20:]; }
    }

    can respond with Root exit {
        if not self.answer {
            self.answer = make_result("UNKNOWN", "", "", "no capability handled this");
        }
        report self.answer;
    }
}
