"""Talks to our own web app's sync API with the device key."""

from urllib.parse import urlsplit

import requests
from sla_contract.schema import CheckResult, FinishResult, StartResult, StartRun

from sla_agent.edusoft_client import USER_AGENT
from sla_agent.errors import DeviceKeyRejected, RunInProgress, ServerUnreachable, UnexpectedAnswer, UpdateRequired
from sla_agent.log import protect

LOCAL_HOSTS = ("localhost", "127.0.0.1")
# The free host can take about a minute to wake up, so allow a long read.
TIMEOUT = (10, 90)
TOO_OLD = "This version of School-Life-Assistant is too old; it is updating itself."


def check_server_url(url):
    """Raise ValueError unless the device key would travel encrypted."""
    parts = urlsplit(url)
    if parts.scheme == "https" and parts.hostname:
        return
    if parts.scheme == "http" and parts.hostname in LOCAL_HOSTS:
        return
    raise ValueError("The web app address must start with https:// (http:// only for localhost).")


class ServerClient:
    def __init__(self, base_url, device_key, session=None):
        check_server_url(base_url)
        protect(device_key)
        self.api = base_url.rstrip("/") + "/api/school/sync"
        self.session = session or requests.Session()
        self.session.headers.update({"Authorization": f"Bearer {device_key}", "User-Agent": USER_AGENT})

    def _call(self, method, path, body=None):
        try:
            response = self.session.request(
                method, self.api + path, json=body, timeout=TIMEOUT, allow_redirects=False
            )
        except (requests.ConnectionError, requests.Timeout) as error:
            raise ServerUnreachable(f"The web app couldn't be reached ({error.__class__.__name__}).") from None
        if response.status_code == 401:
            raise DeviceKeyRejected(
                "The web app rejected this device key. It may have been cancelled on the Devices page."
            )
        if response.status_code == 409:
            raise RunInProgress("The web app says a sync is already running.")
        if response.status_code == 426:
            raise UpdateRequired(TOO_OLD)
        if response.is_redirect or response.status_code >= 400:
            raise UnexpectedAnswer(f"The web app answered HTTP {response.status_code}: {response.text[:300]}",
                                   response.status_code)
        return response.json()

    def check(self):
        return CheckResult.model_validate(self._call("GET", "/check"))

    def start(self, trigger):
        body = StartRun(trigger=trigger).model_dump()
        return StartResult.model_validate(self._call("POST", "/runs", body)).run_id

    def finish(self, run_id, result):
        body = result.model_dump(mode="json", exclude_none=True)
        return FinishResult.model_validate(self._call("POST", f"/runs/{run_id}/finish", body)).status
