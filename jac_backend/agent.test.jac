"""
Tests for agent.jac using MockLLM -- no API key needed, no LLM cost.
Verified passing (jaclang 0.16.7 / byllm 0.6.19): `jac test agent.test.jac`
"""

import from byllm.lib { MockLLM }

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
sem Action.app_name = "The app to open, if type is OPEN_APP. Empty string otherwise.";
sem Action.target = "A specific screen/section within the app, if mentioned. Empty string otherwise.";

# Mock outputs are consumed in order, one per `by llm()` call below.
glob llm = MockLLM(model_name="mockllm", config={"outputs": [
    Action(type=ActionType.OPEN_APP, app_name="Instagram", target="home page"),
    Action(type=ActionType.GO_HOME),
]});

def interpret_command(instruction: str) -> Action by llm();
sem interpret_command = "Interpret a phone-control instruction and map it to a structured phone action.";
sem interpret_command.instruction = "The natural-language instruction the user typed.";

test "instagram home page opens instagram at the home page" {
    result = interpret_command("go to instagram at the home page");
    assert result.type == ActionType.OPEN_APP;
    assert result.app_name == "Instagram";
    assert result.target == "home page";
}

test "go home maps to GO_HOME with no app name" {
    result = interpret_command("go home");
    assert result.type == ActionType.GO_HOME;
    assert result.app_name == "";
}
