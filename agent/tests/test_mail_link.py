"""The sla-mail: link type, in a fake registry (conftest.py): tests never touch the real one."""

from sla_agent import mail_link

ROOT = r"Software\Classes\sla-mail"


def test_register_adds_the_link_type_for_this_user(isolated_agent):
    mail_link.register(r"C:\Python\pythonw.exe")

    keys = isolated_agent.registry.keys
    assert keys[("HKCU", ROOT)] == {"": "URL:School-Life-Assistant email", "URL Protocol": ""}
    assert keys[("HKCU", ROOT + r"\shell\open\command")] == {
        "": r'"C:\Python\pythonw.exe" -m sla_agent open-mail "%1"'}


def test_unregister_removes_it_and_is_fine_when_it_is_gone(isolated_agent):
    mail_link.register("pythonw.exe")

    mail_link.unregister()
    mail_link.unregister()

    assert not [path for _, path in isolated_agent.registry.keys if "sla-mail" in path]


WINDOW_ROOT = r"Software\Classes\sla-agent"


def test_register_window_adds_the_accounts_link_type_beside_sla_mail(isolated_agent):
    mail_link.register(r"C:\Python\pythonw.exe")
    mail_link.register_window(r"C:\Python\pythonw.exe")

    keys = isolated_agent.registry.keys
    assert keys[("HKCU", WINDOW_ROOT)] == {"": "URL:School-Life-Assistant accounts", "URL Protocol": ""}
    assert keys[("HKCU", WINDOW_ROOT + r"\shell\open\command")] == {
        "": r'"C:\Python\pythonw.exe" -m sla_agent window "%1"'}
    assert ("HKCU", ROOT) in keys


def test_unregister_window_leaves_sla_mail_and_is_fine_when_it_is_gone(isolated_agent):
    mail_link.register("pythonw.exe")
    mail_link.register_window("pythonw.exe")

    mail_link.unregister_window()
    mail_link.unregister_window()

    paths = [path for _, path in isolated_agent.registry.keys]
    assert not [path for path in paths if "sla-agent" in path]
    assert ROOT in paths


def test_window_registered_says_whether_the_accounts_link_type_is_there(isolated_agent):
    assert not mail_link.window_registered()

    mail_link.register_window("pythonw.exe")

    assert mail_link.window_registered()
