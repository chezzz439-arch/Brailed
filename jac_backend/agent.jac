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

import from byllm.lib { Model }

glob llm: Model = Model(model_name="claude-sonnet-4-6");
# Model string current as of this writing -- check docs.claude.com if this
# stops working, model names do change over time.

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

def interpret_command(instruction: str) -> Action by llm();
sem interpret_command = "Interpret a phone-control instruction typed in braille by a blind or deaf user, and map it to a structured phone action. If the instruction doesn't clearly match an available action, return UNKNOWN rather than guessing.";
sem interpret_command.instruction = "The natural-language instruction the user typed, e.g. 'go to instagram at the home page'.";

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
