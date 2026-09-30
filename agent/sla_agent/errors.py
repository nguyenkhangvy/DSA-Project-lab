"""Problems the agent can hit. Each maps to an error code in the shared contract."""


class AgentError(Exception):
    code = "unknown"


class BadCredentials(AgentError):
    """EduSoft rejected the student ID or password. Never retry: it could lock the account."""

    code = "bad_credentials"


class ExtraVerification(AgentError):
    """EduSoft asked for a CAPTCHA, one-time code or Microsoft sign-in. Never bypass it."""

    code = "extra_verification"


class SessionExpired(AgentError):
    code = "session_expired"


class NetworkError(AgentError):
    code = "network"


class ParseError(AgentError):
    """A page doesn't look the way the parser expects: EduSoft probably changed it."""

    code = "edusoft_changed"


class SourceChanged(AgentError):
    """Blackboard's or IUPay's answers don't look the way the reader expects: their format probably changed."""

    code = "source_changed"


class UnexpectedRedirect(AgentError):
    """EduSoft tried to send us to another site. We don't follow."""


class ServerError(AgentError):
    """Problems talking to our own web app (not EduSoft)."""


class DeviceKeyRejected(ServerError):
    pass


class ServerUnreachable(ServerError):
    pass


class RunInProgress(ServerError):
    pass


class OutlookNotSetUp(AgentError):
    """Classic Outlook is missing, has no account, or the account chosen at setup is gone."""

    code = "outlook_not_set_up"


class OutlookBlocked(AgentError):
    """Outlook refused to be read, or didn't answer in time (e.g. a security prompt nobody answered).
    The agent never answers Outlook's prompts."""

    code = "outlook_blocked"


class EmailNotFound(AgentError):
    """The email a link points to is no longer in Outlook."""
