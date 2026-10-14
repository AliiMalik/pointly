def test_health_needs_no_key(client):
    r = client.get("/v1/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "model": "test-model"}


def test_missing_key_is_401(client):
    assert client.get("/v1/ping").status_code == 401


def test_wrong_key_is_401(client):
    assert client.get("/v1/ping", headers={"X-Pointly-Key": "nope"}).status_code == 401


def test_right_key_passes(client, auth):
    assert client.get("/v1/ping", headers=auth).status_code == 200
