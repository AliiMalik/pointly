import pytest
from pydantic import ValidationError

from app import masking
from app.schemas import PlanRequest


@pytest.mark.parametrize(
    "raw, masked",
    [
        ("35202-1234567-1", "[number]"),
        ("0300 1234567", "[number]"),
        ("Call 03001234567 now", "Call [number] now"),
        ("Card: 4111 1111 1111 1111", "Card: [number]"),
        ("PIN 123456", "PIN [number]"),
        ("Rs 500", "Rs 500"),
        ("12:45", "12:45"),
        ("12345", "12345"),
        ("Rs 1,000,000", "Rs 1,000,000"),
    ],
)
def test_masking(raw, masked):
    assert masking.mask(raw) == masked


def test_mask_none():
    assert masking.mask(None) is None


def test_example_request_is_valid(example_request):
    req = PlanRequest.model_validate(example_request)
    assert req.screen.package == "com.whatsapp"
    assert [e.id for e in req.screen.elements] == [1, 2, 3]


def test_missing_screen_rejected(example_request):
    del example_request["screen"]
    with pytest.raises(ValidationError):
        PlanRequest.model_validate(example_request)


def test_bad_bounds_rejected(example_request):
    example_request["screen"]["elements"][0]["bounds"] = [0, 1, 2]
    with pytest.raises(ValidationError):
        PlanRequest.model_validate(example_request)


def test_duplicate_ids_rejected(example_request):
    example_request["screen"]["elements"][1]["id"] = 1
    with pytest.raises(ValidationError):
        PlanRequest.model_validate(example_request)


def test_bad_outcome_rejected(example_request):
    example_request["history"][0]["outcome"] = "maybe"
    with pytest.raises(ValidationError):
        PlanRequest.model_validate(example_request)


def test_too_many_elements_is_422(client, auth, example_request):
    example_request["screen"]["elements"] = [
        {"id": i, "text": f"item {i}", "cls": "TextView", "bounds": [0, i, 10, i + 1]} for i in range(1, 122)
    ]
    r = client.post("/v1/plan", json=example_request, headers=auth)
    assert r.status_code == 422


def test_bad_request_is_422(client, auth):
    r = client.post("/v1/plan", json={"goal_text": "x"}, headers=auth)
    assert r.status_code == 422
