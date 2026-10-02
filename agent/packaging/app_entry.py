"""PyInstaller's entry point for the app (agent/packaging/build.py): the same commands as `sla-agent`, with an
unexpected error logged instead of shown in a box (cli.app_main)."""

import sys

from sla_agent.cli import app_main

sys.exit(app_main())
