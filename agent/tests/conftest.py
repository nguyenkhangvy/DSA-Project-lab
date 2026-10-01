from pathlib import Path

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

    def OpenKey(self, root, path):
        if (root, path) not in self.keys:
            raise FileNotFoundError(path)
        return self.Key((root, path))

    def DeleteKey(self, root, path):
        if (root, path) not in self.keys:
            raise FileNotFoundError(path)
        if any(other[0] == root and other[1].startswith(path + "\\") for other in self.keys):
            raise OSError("has subkeys")
        del self.keys[(root, path)]


class FakeShortcut:
    def __init__(self, path):
        self.path = path
        self.TargetPath = self.Arguments = self.WorkingDirectory = self.Description = ""

    def Save(self):
        Path(self.path).write_text(f"{self.TargetPath} {self.Arguments}\n{self.WorkingDirectory}", encoding="utf-8")


class FakeShell:
    """Stands in for Windows' WScript.Shell: a shortcut becomes a small text file in the test's own folders. The
    Desktop has Vietnamese letters in its path, like OneDrive's "Máy tính" on the student's laptop."""

    FOLDERS = {"Desktop": "OneDrive/Máy tính", "Programs": "Start Menu/Programs"}

    def __init__(self, root):
        self.root = root

    def SpecialFolders(self, name):
        folder = self.root / self.FOLDERS[name]
        folder.mkdir(parents=True, exist_ok=True)
        return str(folder)

    def CreateShortcut(self, path):
        return FakeShortcut(path)


@pytest.fixture(autouse=True)
def isolated_agent(tmp_path, monkeypatch):
    """Every agent test gets a fake keyring, a fake registry, a fake Windows shell and its own agent folder:
    tests never touch the real Credential Manager, registry, Desktop, Start menu or %LOCALAPPDATA%."""
    from sla_agent import mail_link, shortcuts

    fake = MemoryKeyring()
    previous = keyring.get_keyring()
    keyring.set_keyring(fake)
    registry = MemoryRegistry()
    monkeypatch.setattr(mail_link, "_winreg", lambda: registry)
    shell = FakeShell(tmp_path / "windows")
    monkeypatch.setattr(shortcuts, "_shell", lambda: shell)
    monkeypatch.setenv("SLA_AGENT_HOME", str(tmp_path / "agent-home"))
    fake.registry = registry
    fake.shell = shell
    yield fake
    keyring.set_keyring(previous)
