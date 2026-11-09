#!/usr/bin/env python3
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

STATE = Path("/var/lib/chicky")
PAUSED = STATE / "update-paused.json"
REPO = "OccupiedPorcupine/CS203-Vincent-s-Disciples"


def order(tag):
    match = re.fullmatch(r"[a-f0-9]{40}-([0-9]+)-([0-9]+)", tag)
    if not match:
        raise RuntimeError("Invalid release tag")
    return int(match[1]), int(match[2])


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("--check", "--apply"):
        raise RuntimeError("Usage: chicky-update.py --check|--apply")
    if os.geteuid() != 0:
        raise RuntimeError("Run with sudo")

    apply = sys.argv[1] == "--apply"
    os.umask(0o077)

    if apply:
        lock = (STATE / "update.lock").open("a")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Another update check is running.")
            return

    current = json.loads(
        (STATE / "current.json").read_text()
    )["release_tag"]
    order(current)

    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/releases?per_page=100",
        headers={
            "User-Agent": "chicky-updater",
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(4 * 1024 * 1024 + 1)
    if len(data) > 4 * 1024 * 1024:
        raise RuntimeError("Release list exceeded size limit")

    candidates = []
    for release in json.loads(data):
        if release["draft"] or release["prerelease"]:
            continue
        tag = release["tag_name"]
        if not re.fullmatch(r"aws-[a-f0-9]{40}-[0-9]+-[0-9]+", tag):
            continue
        if any(
            asset["name"] == "deployment.json"
            and asset["state"] == "uploaded"
            for asset in release["assets"]
        ):
            candidates.append(tag[4:])

    if not candidates:
        raise RuntimeError("No completed AWS releases found")

    latest = max(candidates, key=order)
    print("Currently deployed:", current, flush=True)
    print("Newest published:  ", latest, flush=True)

    if PAUSED.exists():
        raise RuntimeError(
            "Automatic updates paused after an earlier attempt. "
            "Review /var/lib/chicky/update-paused.json before continuing."
        )

    if order(latest) <= order(current):
        print("Already up to date; no deployment needed.")
        return

    if not apply:
        print("New release available. Check only; nothing changed.")
        return

    # Leave this marker in place if deployment fails or is interrupted.
    with PAUSED.open("x") as file:
        json.dump({"from": current, "attempted": latest}, file, indent=2)

    subprocess.run(
        ["/usr/local/sbin/chicky-deploy.py", latest],
        check=True,
    )

    recorded = json.loads((STATE / "current.json").read_text())
    if recorded["release_tag"] != latest:
        raise RuntimeError("Deployment state did not record the new release")

    PAUSED.unlink()
    print("Update completed successfully.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)