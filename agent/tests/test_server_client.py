import json

import pytest
import requests
import responses
from sla_contract.schema import FinishRun

from sla_agent.errors import DeviceKeyRejected, RunInProgress, ServerError, ServerUnreachable, UpdateRequired
from sla_agent.server_client import ServerClient

BASE = "https://sla.example.com"
API = f"{BASE}/api/school/sync"


@pytest.fixture
def server():
    return ServerClient(BASE, "sla_key-123")


@responses.activate
def test_check_sends_the_device_key_and_reads_the_answer(server):
    responses.get(f"{API}/check", json={"due": True, "reason": "interval", "interval_hours": 12})

    result = server.check()

    assert (result.due, result.reason, result.interval_hours) == (True, "interval", 12)
    assert responses.calls[0].request.headers["Authorization"] == "Bearer sla_key-123"


@responses.activate
def test_start_returns_the_run_id(server):
    responses.post(f"{API}/runs", json={"run_id": 7}, status=201)

    assert server.start("manual") == 7
    assert json.loads(responses.calls[0].request.body) == {"trigger": "manual"}


@responses.activate
def test_finish_sends_the_result_without_empty_fields(server):
    responses.post(f"{API}/runs/7/finish", json={"status": "failed"})
    result = FinishRun(error_code="network", error_message="EduSoft couldn't be reached.")

    assert server.finish(7, result) == "failed"
    assert json.loads(responses.calls[0].request.body) == {
        "schema_version": 1, "error_code": "network", "error_message": "EduSoft couldn't be reached.",
    }


@responses.activate
def test_a_cancelled_key_raises_device_key_rejected(server):
    responses.get(f"{API}/check", json={"error": "invalid_device_key"}, status=401)

    with pytest.raises(DeviceKeyRejected):
        server.check()


@responses.activate
def test_a_run_already_in_progress_raises(server):
    responses.post(f"{API}/runs", json={"error": "run_in_progress"}, status=409)

    with pytest.raises(RunInProgress):
        server.start("scheduled")


@responses.activate
def test_an_unreachable_server_raises_server_unreachable(server):
    responses.get(f"{API}/check", body=requests.ConnectionError("no route"))

    with pytest.raises(ServerUnreachable):
        server.check()


@responses.activate
def test_a_redirect_is_not_followed_so_the_key_stays_put(server):
    responses.get(f"{API}/check", status=302, headers={"Location": "https://elsewhere.example/check"})

    with pytest.raises(ServerError):
        server.check()

    assert len(responses.calls) == 1


@pytest.mark.parametrize("url", ["http://sla.example.com", "ftp://sla.example.com", "sla.example.com"])
def test_the_device_key_is_never_sent_over_plain_http(url):
    with pytest.raises(ValueError):
        ServerClient(url, "sla_key-123")


@pytest.mark.parametrize("url", ["http://localhost:5000", "http://127.0.0.1:5000/"])
def test_plain_http_is_allowed_for_a_local_development_server(url):
    assert ServerClient(url, "sla_key-123") is not None


@responses.activate
def test_a_version_the_website_no_longer_accepts_raises_update_required(server):
    responses.get(f"{API}/check", json={"error": "update_required"}, status=426)

    with pytest.raises(UpdateRequired, match="too old"):
        server.check()
