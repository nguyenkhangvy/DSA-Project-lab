import json
import os
from pathlib import Path

from app.config import load_config

# Tests use a throwaway in-memory SQLite database by default.
# CI sets TEST_DATABASE_URL to a real MySQL database.
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL", "sqlite://")


def make_config(**overrides):
    config = load_config({"SECRET_KEY": "test-secret", "DATABASE_URL": TEST_DATABASE_URL})
    config.update(TESTING=True, WTF_CSRF_ENABLED=False)
    config.update(overrides)
    return config


def register(client, email="an@example.com", display_name="An", password="correct-horse", confirm=None):
    return client.post(
        "/auth/register",
        data={
            "email": email,
            "display_name": display_name,
            "password": password,
            "confirm": password if confirm is None else confirm,
        },
    )


def login(client, email="an@example.com", password="correct-horse", next_url=None):
    query = {} if next_url is None else {"next": next_url}
    return client.post("/auth/login", query_string=query, data={"email": email, "password": password})


def logout(client):
    return client.post("/auth/logout")


def make_user(app, email="an@example.com", display_name="An"):
    from app.auth.models import User
    from app.extensions import db

    with app.app_context():
        user = User(email=email, display_name=display_name)
        user.set_password("correct-horse")
        db.session.add(user)
        db.session.commit()
        return user.id


def make_device(app, user_id, name="My laptop"):
    """Create a sync device the way the Devices page does; returns the raw key."""
    from app.extensions import db
    from app.school.services.devices import create_device

    with app.app_context():
        _, raw_key = create_device(user_id, name)
        db.session.commit()
        return raw_key


def api(client, method, path, key=None, json=None, **kwargs):
    headers = {} if key is None else {"Authorization": f"Bearer {key}"}
    return client.open(f"/api/school/sync{path}", method=method, headers=headers, json=json, **kwargs)


# Uploads from contract/samples/, which the Java website's tests read too.
SAMPLES = Path(__file__).resolve().parents[1] / "contract" / "samples"
BB = "https://blackboard.hcmiu.edu.vn"


def _sample(name):
    return json.loads((SAMPLES / name).read_text(encoding="utf-8"))


# A complete, valid upload from the agent. Times are Vietnam time (+07:00).
def full_payload():
    return _sample("finish-edusoft.json")


# A valid Blackboard section as the agent uploads it.
def blackboard_payload():
    return _sample("finish-blackboard.json")["blackboard"]["data"]
