import json

from app import prompts
from app.planner import CANT_HELP_EN, validate_plan
from app.schemas import Element
from tests.conftest import FakeProvider, LLMError


def post(client, req, auth):
    return client.post("/v1/plan", json=req, headers=auth)


def test_plan_needs_key(make_client, example_request, good_answer):
    c = make_client(FakeProvider(good_answer))
    assert c.post("/v1/plan", json=example_request).status_code == 401
    assert c.post("/v1/plan", json=example_request, headers={"X-Pointly-Key": "wrong"}).status_code == 401


def test_good_plan(make_client, auth, example_request, good_answer):
    fake = FakeProvider(good_answer)
    r = post(make_client(fake), example_request, auth)
    assert r.status_code == 200
    body = r.json()
    assert body["action"] == "tap"
    assert body["element_id"] == 2
    assert body["say_en"] == "Tap Sara's name"
    assert body["goal_key"] == "whatsapp_send_photo"
    assert isinstance(body["latency_ms"], int)
    assert len(fake.calls) == 1


def test_few_shot_turns_come_first(make_client, auth, example_request, good_answer):
    fake = FakeProvider(good_answer)
    post(make_client(fake), example_request, auth)
    turns = fake.calls[0]
    assert [t.role for t in turns] == ["user", "model", "user", "model", "user"]
    assert turns[-1].text.startswith("GOAL: Beti Sara ko WhatsApp pe photo bhejni hai\nSTEP: 2\nHISTORY:\n1.")
    assert '#2 [View, clickable] "Sara | Sara · Kal milte hain" @ (0,420)-(1080,560)' in turns[-1].text


def test_unknown_element_id_retries_once(make_client, auth, example_request, good_answer):
    bad = dict(good_answer, element_id=99)
    fake = FakeProvider(bad, good_answer)
    r = post(make_client(fake), example_request, auth)
    assert r.status_code == 200
    assert r.json()["element_id"] == 2
    assert len(fake.calls) == 2
    retry_turns = fake.calls[1]
    assert retry_turns[-2].role == "model"
    assert retry_turns[-1].role == "user"
    assert "element_id 99 is not on the screen" in retry_turns[-1].text


def test_invalid_twice_is_cant_help(make_client, auth, example_request, good_answer):
    bad = dict(good_answer, element_id=99)
    fake = FakeProvider(bad, "not json at all")
    r = post(make_client(fake), example_request, auth)
    assert r.status_code == 200
    body = r.json()
    assert body["action"] == "cant_help"
    assert body["element_id"] is None
    assert body["say_en"] == CANT_HELP_EN
    assert len(fake.calls) == 2


def test_non_json_then_good(make_client, auth, example_request, good_answer):
    fake = FakeProvider("oops", good_answer)
    r = post(make_client(fake), example_request, auth)
    assert r.json()["action"] == "tap"


def test_provider_down_is_503(make_client, auth, example_request):
    fake = FakeProvider(LLMError("quota"))
    r = post(make_client(fake), example_request, auth)
    assert r.status_code == 503


def test_no_api_key_is_503(client, auth, example_request):
    assert post(client, example_request, auth).status_code == 503


def test_long_numbers_never_reach_the_model(make_client, auth, example_request, good_answer):
    example_request["goal_text"] = "0300 1234567 pe call karni hai"
    example_request["screen"]["elements"][0]["text"] = "Account 1234 5678 9012"
    fake = FakeProvider(good_answer)
    post(make_client(fake), example_request, auth)
    sent = fake.calls[0][-1].text
    assert "1234567" not in sent and "5678" not in sent
    assert "[number] pe call karni hai" in sent
    assert '"Account [number]"' in sent


def test_log_line_has_no_element_text(make_client, auth, example_request, good_answer, settings):
    post(make_client(FakeProvider(good_answer)), example_request, auth)
    lines = (settings.log_dir / "plan.jsonl").read_text(encoding="utf-8").splitlines()
    rec = json.loads(lines[-1])
    for key in ("ts", "session_id", "step_index", "package", "n_elements", "goal_text", "action",
                "element_id", "goal_key", "confidence", "latency_ms", "retried", "valid"):
        assert key in rec, key
    assert rec["valid"] is True and rec["retried"] is False and rec["n_elements"] == 3
    assert "elements" not in rec
    assert "Kal milte hain" not in lines[-1]


def test_log_screens_includes_masked_elements(make_client, auth, example_request, good_answer, settings):
    settings.log_screens = True
    example_request["screen"]["elements"][0]["text"] = "0300 1234567"
    post(make_client(FakeProvider(good_answer)), example_request, auth)
    rec = json.loads((settings.log_dir / "plan.jsonl").read_text(encoding="utf-8").splitlines()[-1])
    assert rec["elements"][0]["text"] == "[number]"


IDS = {1, 2, 3}


def test_done_drops_element_id(good_answer):
    plan, err = validate_plan(dict(good_answer, action="done", element_id=2), IDS)
    assert err is None and plan.element_id is None


def test_scroll_needs_direction(good_answer):
    plan, err = validate_plan(dict(good_answer, action="scroll", element_id=1), IDS)
    assert plan is None and "scroll_direction" in err
    plan, err = validate_plan(dict(good_answer, action="scroll", element_id=1, scroll_direction="down"), IDS)
    assert err is None and plan.scroll_direction == "down"


def test_tap_needs_element(good_answer):
    plan, err = validate_plan(dict(good_answer, element_id=None), IDS)
    assert plan is None and "element_id is required" in err


def test_too_many_words(good_answer):
    long = "Please tap the big round green button that you can see right down at the bottom"
    plan, err = validate_plan(dict(good_answer, say_en=long), IDS)
    assert plan is None and "say_en" in err


def test_technical_words_rejected(good_answer):
    plan, err = validate_plan(dict(good_answer, say_en="Tap element #2"), IDS)
    assert plan is None


def test_unknown_action_rejected(good_answer):
    plan, err = validate_plan(dict(good_answer, action="click"), IDS)
    assert plan is None and "action" in err


def test_goal_key_and_confidence_normalized(good_answer):
    plan, err = validate_plan(dict(good_answer, goal_key="WhatsApp Send-Photo", confidence=1.7), IDS)
    assert err is None and plan.goal_key == "whatsapp_send_photo" and plan.confidence == 1.0


def test_element_line_format():
    e = Element(id=3, desc="New chat", view_id="fab", cls="ImageButton", clickable=True, bounds=[900, 2100, 1040, 2240])
    assert prompts.element_line(e) == '#3 [ImageButton, clickable] "New chat | id: fab" @ (900,2100)-(1040,2240)'
    e2 = Element(id=4, cls="Switch", clickable=True, checked=False, bounds=[0, 0, 1, 1])
    assert prompts.element_line(e2) == '#4 [Switch, clickable, unchecked] "(no label)" @ (0,0)-(1,1)'


def test_few_shot_answers_are_valid():
    for user, model in prompts.FEW_SHOT:
        ids = {int(line.split()[0][1:]) for line in user.splitlines() if line.startswith("#")}
        plan, err = validate_plan(json.loads(model), ids)
        assert err is None, err


def test_mock_provider_taps_matching_row(settings, auth):
    from fastapi.testclient import TestClient

    from app.main import create_app

    settings.llm_provider = "mock"
    c = TestClient(create_app(settings))
    req = {
        "session_id": "m", "goal_text": "Phone ke likhai bari karni hai", "step_index": 1,
        "screen": {"package": "com.android.settings", "width": 1080, "height": 2400, "elements": [
            {"id": 1, "label": "Network & internet · Mobile, Wi-Fi", "cls": "View", "clickable": True, "bounds": [0, 0, 10, 10]},
            {"id": 2, "label": "Display & touch · Dark theme, font size", "cls": "View", "clickable": True, "bounds": [0, 20, 10, 30]},
        ]},
    }
    body = c.post("/v1/plan", json=req, headers=auth).json()
    assert body["action"] == "tap" and body["element_id"] == 2
    assert c.get("/v1/health").json()["model"] == "mock"


def test_mock_script_rules(settings, auth, tmp_path, monkeypatch):
    from fastapi.testclient import TestClient

    from app.main import create_app

    script = tmp_path / "s.json"
    script.write_text(
        '[{"goal": "likhai", "title": "Display & touch", "label": "Display size", "say_en": "Tap this", "goal_key": "settings_font_size"},'
        ' {"goal": "likhai", "package": "com.android.settings", "action": "done", "say_en": "Well done"}]',
        encoding="utf-8",
    )
    monkeypatch.setenv("MOCK_SCRIPT", str(script))
    settings.llm_provider = "mock"
    c = TestClient(create_app(settings))
    screen = {"package": "com.android.settings", "window_title": "Display & touch", "width": 1080, "height": 2400, "elements": [
        {"id": 1, "label": "Dark theme", "cls": "View", "clickable": True, "bounds": [0, 0, 10, 10]},
        {"id": 2, "label": "Display size & text · Font size", "cls": "View", "clickable": True, "bounds": [0, 20, 10, 30]},
    ]}
    req = {"session_id": "m", "goal_text": "Phone ke likhai bari karni hai", "step_index": 2, "screen": screen}
    body = c.post("/v1/plan", json=req, headers=auth).json()
    assert (body["action"], body["element_id"], body["goal_key"]) == ("tap", 2, "settings_font_size")
    req["screen"] = dict(screen, window_title="Display size & text")
    assert c.post("/v1/plan", json=req, headers=auth).json()["action"] == "done"


def test_roman_text_in_say_ur_is_rejected(good_answer):
    plan, err = validate_plan(dict(good_answer, say_ur="Sara ke naam pe dabayein"), IDS)
    assert plan is None and "Urdu script" in err


def test_app_name_in_urdu_sentence_is_fine(good_answer):
    plan, err = validate_plan(dict(good_answer, say_ur="سبز WhatsApp آئیکن دبائیں"), IDS)
    assert err is None


def test_other_scripts_in_say_en_are_rejected(good_answer):
    plan, err = validate_plan(dict(good_answer, say_en="Tap the plus sign ాన్"), IDS)
    assert plan is None and "plain English" in err


def test_urdu_text_in_say_en_is_rejected(good_answer):
    plan, err = validate_plan(dict(good_answer, say_en="سارہ کے نام پر دبائیں"), IDS)
    assert plan is None and "say_en" in err


def test_roman_in_urdu_triggers_retry(make_client, auth, example_request, good_answer):
    fake = FakeProvider(dict(good_answer, say_ur="Sara ke naam pe dabayein"), good_answer)
    body = post(make_client(fake), example_request, auth).json()
    assert body["say_ur"] == good_answer["say_ur"]
    assert "Urdu script" in fake.calls[1][-1].text


def test_drag_needs_slider_element_and_direction(good_answer):
    plan, err = validate_plan(dict(good_answer, action="drag", element_id=2), IDS)
    assert plan is None and 'scroll_direction is required for "drag"' in err
    plan, err = validate_plan(dict(good_answer, action="drag", element_id=2, scroll_direction="right"), IDS)
    assert err is None and plan.scroll_direction == "right"
    plan, err = validate_plan(dict(good_answer, action="drag", element_id=None, scroll_direction="right"), IDS)
    assert plan is None and "element_id is required" in err


def test_slider_shows_its_value_range():
    e = Element(id=21, text="2", cls="SeekBar", range=[0, 4, 2], bounds=[120, 1775, 959, 1859])
    assert prompts.element_line(e) == '#21 [SeekBar, slider 2 of 0-4] "2" @ (120,1775)-(959,1859)'
