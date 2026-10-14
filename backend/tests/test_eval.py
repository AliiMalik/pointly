import json

from eval import eval_planner


def snapshot(goal, expected, elements, package="com.android.settings"):
    return {
        "goal_text": goal,
        "expected": expected,
        "history": [],
        "screen": {"package": package, "width": 1080, "height": 2400, "elements": elements},
        "captured_at_ms": 0,
        "read_ms": 5,
    }


SETTINGS = [
    {"id": 1, "label": "Network & internet · Mobile, Wi-Fi", "cls": "View", "clickable": True, "bounds": [0, 0, 1080, 100]},
    {"id": 2, "label": "Display & touch · Dark theme, font size", "cls": "View", "clickable": True, "bounds": [0, 100, 1080, 200]},
]


def write(tmp_path, name, data):
    (tmp_path / f"{name}.json").write_text(json.dumps(data), encoding="utf-8")


def test_eval_reports_accuracy_and_failures(tmp_path, capsys):
    snaps = tmp_path / "snaps"
    snaps.mkdir()
    write(snaps, "font_ok", snapshot("Phone ke likhai bari karni hai", [{"action": "tap", "element_id": 2}], SETTINGS))
    write(snaps, "wifi_ok", snapshot("WiFi on karna hai", [{"action": "tap", "element_id": 1}], SETTINGS))
    write(snaps, "wifi_wrong", snapshot("WiFi on karna hai", [{"action": "tap", "element_id": 2}], SETTINGS))
    write(snaps, "unlabelled", snapshot("", [], SETTINGS))
    (snaps / "broken.json").write_text("{nope", encoding="utf-8")

    out = tmp_path / "reports"
    code = eval_planner.main(["--snapshots", str(snaps), "--mock", "--out", str(out), "--json"])
    assert code == 0
    printed = capsys.readouterr().out
    assert "Snapshots evaluated: 3  (skipped 2, errors 0)" in printed
    assert "Step accuracy:   66.7%" in printed
    assert "Action accuracy: 100.0%" in printed
    assert "Invalid output:  0.0%" in printed

    csvs = list(out.glob("failures-*.csv"))
    assert len(csvs) == 1
    body = csvs[0].read_text(encoding="utf-8")
    assert "wifi_wrong" in body and "font_ok" not in body


def test_matches_accepts_any_element_when_unspecified():
    assert eval_planner.matches([{"action": "home"}], "home", None) == (True, True)
    assert eval_planner.matches([{"action": "scroll"}], "scroll", 7) == (True, True)
    assert eval_planner.matches([{"action": "tap", "element_id": 3}], "tap", 4) == (False, True)


def test_percentile():
    assert eval_planner.percentile([], 50) is None
    assert eval_planner.percentile([100, 300, 200, 400], 50) == 200
    assert eval_planner.percentile([100, 300, 200, 400], 90) == 400


def test_bundled_snapshots_are_labelled_and_valid():
    cases, skipped = eval_planner.load_cases(eval_planner.DEFAULT_SNAPSHOTS)
    assert not [s for s in skipped if "invalid" in s or "not JSON" in s], skipped
