#!/bin/sh
# Updates Chicky on the EC2 instance to the latest main and restarts what changed. Run it on the
# server from anywhere in the checkout; .github/workflows/deploy-ec2.yml runs it after each merge.
set -eu

# In a function, so the shell has read all of it before `git reset` replaces this file.
deploy() {
  cd "$(dirname "$0")"
  git fetch --quiet origin main
  git checkout --quiet main
  git reset --quiet --hard origin/main

  # Builds first, then replaces the running containers, so the site stays up during the build.
  docker compose up -d --build --remove-orphans
  # Old images add up quickly (the ML image is about 1 GB).
  docker image prune -f > /dev/null

  echo "Deployed $(git log --oneline -1)"
}

deploy "$@"
