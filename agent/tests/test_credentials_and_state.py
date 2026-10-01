import copy

from sla_agent import credentials
from sla_agent.state import State, agent_home, load_state, save_state


def test_edusoft_password_goes_to_the_keyring_under_the_student_id(isolated_agent):
    credentials.save_edusoft("ITITIU20001", "s3cret-pass")

    assert isolated_agent.entries == {("SchoolLifeAssistant-EduSoft", "ITITIU20001"): "s3cret-pass"}
    assert credentials.load_edusoft("ITITIU20001") == "s3cret-pass"


def test_device_key_goes_to_the_keyring_under_the_server_address(isolated_agent):
    credentials.save_device_key("https://sla.example.com", "sla_key-123")

    assert credentials.load_device_key("https://sla.example.com") == "sla_key-123"
    assert ("SchoolLifeAssistant-DeviceKey", "https://sla.example.com") in isolated_agent.entries


def test_forget_removes_both_secrets_even_if_one_is_missing(isolated_agent):
    credentials.save_edusoft("ITITIU20001", "s3cret-pass")

    credentials.forget("ITITIU20001", "https://sla.example.com")

    assert isolated_agent.entries == {}


def test_state_file_round_trip_never_contains_secrets(isolated_agent):
    credentials.save_edusoft("ITITIU20001", "s3cret-pass")
    state = State(server_url="https://sla.example.com", student_id="ITITIU20001", paused="bad_credentials")

    save_state(state)

    assert load_state() == state
    assert "s3cret-pass" not in (agent_home() / "state.json").read_text(encoding="utf-8")


def test_missing_state_file_gives_an_empty_state():
    assert load_state() == State()


def test_blackboard_password_goes_to_its_own_keyring_entry(isolated_agent):
    credentials.save_blackboard("bbuser", "bb-s3cret")

    assert isolated_agent.entries == {("SchoolLifeAssistant-Blackboard", "bbuser"): "bb-s3cret"}
    assert credentials.load_blackboard("bbuser") == "bb-s3cret"


def test_forget_also_removes_the_blackboard_password(isolated_agent):
    credentials.save_blackboard("bbuser", "bb-s3cret")

    credentials.forget("ITITIU20001", "https://sla.example.com", "bbuser")

    assert isolated_agent.entries == {}


def test_blackboard_state_round_trips_and_old_state_files_still_load():
    state = State(server_url="https://sla.example.com", student_id="S", blackboard_username="bbuser",
                  blackboard_paused="bad_credentials", registered_courses=[["IT093IU", "02"]])
    save_state(state)
    assert load_state() == state

    (agent_home() / "state.json").write_text('{"server_url": "https://x.example", "paused": null}', encoding="utf-8")
    assert load_state() == State(server_url="https://x.example")


def test_saving_with_what_was_loaded_keeps_the_fields_someone_else_changed_meanwhile():
    save_state(State(server_url="https://sla.example.com", student_id="ITITIU20001", paused="bad_credentials"))
    mine = load_state()  # a sync starts
    loaded = copy.deepcopy(mine)
    other = load_state()  # the window saves a new password and a Blackboard login meanwhile
    other.paused, other.blackboard_username = None, "ititiu20001"
    save_state(other)
    mine.last_result = {"status": "success"}  # the sync's own change

    save_state(mine, loaded)

    saved = load_state()
    assert (saved.paused, saved.blackboard_username, saved.last_result) == (None, "ititiu20001", {"status": "success"})
    assert mine == saved
