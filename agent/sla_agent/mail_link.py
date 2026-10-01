r"""The sla-mail: and sla-agent: link types, registered for this Windows user only
(HKEY_CURRENT_USER\Software\Classes), so no admin rights are needed.

- sla-mail: Mailbox's "Open in Outlook" button. A link runs `sla-agent open-mail "sla-mail:<Outlook ID>"`, which
  only shows that email (see outlook_reader.open_email).
- sla-agent: the website's "Open Accounts on this laptop" button. A link runs
  `sla-agent window "sla-agent:accounts"`, which only opens the School-Life-Assistant window."""

KEY = r"Software\Classes\sla-mail"
WINDOW_KEY = r"Software\Classes\sla-agent"
SUBKEYS = (r"\shell\open\command", r"\shell\open", r"\shell", "")  # deepest first, for deleting


def _winreg():
    import winreg

    return winreg


def command(python_exe):
    return f'"{python_exe}" -m sla_agent open-mail "%1"'


def window_command(python_exe):
    return f'"{python_exe}" -m sla_agent window "%1"'


def _register(key_path, description, command_line):
    registry = _winreg()
    with registry.CreateKey(registry.HKEY_CURRENT_USER, key_path) as key:
        registry.SetValueEx(key, "", 0, registry.REG_SZ, description)
        registry.SetValueEx(key, "URL Protocol", 0, registry.REG_SZ, "")
    with registry.CreateKey(registry.HKEY_CURRENT_USER, key_path + r"\shell\open\command") as key:
        registry.SetValueEx(key, "", 0, registry.REG_SZ, command_line)


def _unregister(key_path):
    registry = _winreg()
    for subkey in SUBKEYS:
        try:
            registry.DeleteKey(registry.HKEY_CURRENT_USER, key_path + subkey)
        except FileNotFoundError:
            pass


def register(python_exe):
    _register(KEY, "URL:School-Life-Assistant email", command(python_exe))


def unregister():
    _unregister(KEY)


def register_window(python_exe):
    _register(WINDOW_KEY, "URL:School-Life-Assistant accounts", window_command(python_exe))


def window_registered():
    """Whether the sla-agent: link type is registered for this Windows user."""
    registry = _winreg()
    try:
        with registry.OpenKey(registry.HKEY_CURRENT_USER, WINDOW_KEY + r"\shell\open\command"):
            return True
    except FileNotFoundError:
        return False


def unregister_window():
    _unregister(WINDOW_KEY)
