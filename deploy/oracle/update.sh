#!/usr/bin/env bash
# Puts the newest main online on the Oracle VM (README, "Always on: Oracle Cloud"): pulls it, builds the site while
# the old version keeps running, then restarts it, which logs everyone out.
set -euo pipefail
cd "$(dirname "$0")"

git pull --ff-only
docker compose up -d --build
docker image prune -f  # the previous version's image
