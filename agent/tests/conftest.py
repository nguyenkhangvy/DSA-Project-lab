import keyring
import pytest
from keyring.backend import KeyringBackend
from keyring.errors import PasswordDeleteError


class MemoryKeyring(KeyringBackend):
    """Stands in for Windows Credential Manager during tests."""

    priority = 1

    def __init__(self):
        super().__init__()
        self.entries = {}

    def set_password(self, service, username, password):
        self.entries[(service, username)] = password

    def get_password(self, service, username):
        return self.entries.get((service, username))

    def delete_password(self, service, username):
        if (service, username) not in self.entries:
            raise PasswordDeleteError("not found")
        del self.entries[(service, username)]


class MemoryRegistry:
    """Stands in for winreg (the Windows registry) during tests: keys are paths, values a dict per key."""

    HKEY_CURRENT_USER = "HKCU"
    REG_SZ = 1

    def __init__(self):
        self.keys = {}

    class Key:
        def __init__(self, path):
            self.path = path

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    def CreateKey(self, root, path):
        parts = path.split("\\")
        for end in range(1, len(parts) + 1):
            self.keys.setdefault((root, "\\".join(parts[:end])), {})
        return self.Key((root, path))

    def SetValueEx(self, key, name, reserved, kind, value):
        self.keys[key.path][name] = value

    def DeleteKey(self, root, path):
        if (root, path) not in self.keys:
            raise FileNotFoundError(path)
        if any(other[0] == root and other[1].startswith(path + "\\") for other in self.keys):
            raise OSError("has subkeys")
        del self.keys[(root, path)]


@pytest.fixture(autouse=True)
def isolated_agent(tmp_path, monkeypatch):
    """Every agent test gets a fake keyring, a fake registry and its own agent folder:
    tests never touch the real Credential Manager, registry or %LOCALAPPDATA%."""
    from sla_agent import mail_link

    fake = MemoryKeyring()
    previous = keyring.get_keyring()
    keyring.set_keyring(fake)
    registry = MemoryRegistry()
    monkeypatch.setattr(mail_link, "_winreg", lambda: registry)
    monkeypatch.setenv("SLA_AGENT_HOME", str(tmp_path / "agent-home"))
    fake.registry = registry
    yield fake
    keyring.set_keyring(previous)
