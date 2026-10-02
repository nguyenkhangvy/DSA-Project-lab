"""PyInstaller's entry point for the setup (agent/packaging/build.py): installer.main."""

import sys

from sla_agent.installer import main

sys.exit(main())
