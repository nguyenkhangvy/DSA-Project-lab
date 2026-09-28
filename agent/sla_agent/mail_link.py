r"""The sla-mail: link type, so Mailbox's "Open in Outlook" button opens an email on this laptop.

It is registered for this Windows user only (HKEY_CURRENT_USER\Software\Classes\sla-mail), so no admin
rights are needed. A link runs `sla-agent open-mail "sla-mail:<Outlook ID>"`, which only shows that email
(see outlook_reader.open_email)."""

KEY = r"Software\Classes\sla-mail"
SUBKEYS = (r"\shell\open\command", r"\shell\open", r"\shell", "")  # deepest first, for deleting


def _winreg():
    import winreg

    return winreg


def command(python_exe):
    return f'"{python_exe}" -m sla_agent open-mail "%1"'


def register(python_exe):
    registry = _winreg()
    with registry.CreateKey(registry.HKEY_CURRENT_USER, KEY) as key:
        registry.SetValueEx(key, "", 0, registry.REG_SZ, "URL:School-Life-Assistant email")
        registry.SetValueEx(key, "URL Protocol", 0, registry.REG_SZ, "")
    with registry.CreateKey(registry.HKEY_CURRENT_USER, KEY + r"\shell\open\command") as key:
        registry.SetValueEx(key, "", 0, registry.REG_SZ, command(python_exe))


def unregister():
    registry = _winreg()
    for subkey in SUBKEYS:
        try:
            registry.DeleteKey(registry.HKEY_CURRENT_USER, KEY + subkey)
        except FileNotFoundError:
            pass
