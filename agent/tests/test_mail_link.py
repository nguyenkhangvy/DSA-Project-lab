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
