"""PyInstaller's entry point for the app (agent/packaging/build.py): the same commands as `sla-agent`."""

import sys

from sla_agent.cli import main

sys.exit(main())
