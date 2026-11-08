#!/usr/bin/env python3
"""Run on EC2 as root: chicky-deploy.py <release-tag>."""

import fcntl
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time
import urllib.request

REPO = "OccupiedPorcupine/CS203-Vincent-s-Disciples"
REGISTRY = "ghcr.io/occupiedporcupine"
CONFIG = Path("/etc/chicky")
STATE = Path("/var/lib/chicky")
SERVICES = ("web", "backend", "ml", "caddy")


def run(args, capture=False):
    return subprocess.check_output(args, text=True) if capture else subprocess.run(
        args, check=True
    )


def fetch(url, limit):
    request = urllib.request.Request(
        url, headers={"User-Agent": "chicky-deployer"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise RuntimeError("Download exceeded size limit")
    return data


def write_json(path, data):
    with path.open("x") as file:
        json.dump(data, file, indent=2)


def compose(override):
    return [
        "docker", "compose",
        "--env-file", str(CONFIG / "production.env"),
        "-f", str(CONFIG / "compose.yaml"),
        "-f", str(override),
    ]


def override(images):
    services = {
        name: {"image": image, "pull_policy": "never"}
        for name, image in images.items()
    }
    services["backend"]["environment"] = {
        "SPRING_FLYWAY_BASELINE_ON_MIGRATE": "false"
    }
    return {"services": services}


def migrations(commit):
    archive = fetch(
        f"https://codeload.github.com/{REPO}/tar.gz/{commit}",
        64 * 1024 * 1024,
    )
    result = {}
    marker = "/backend/src/main/resources/db/migration/"
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
        for member in tar:
            if not member.isfile() or marker not in member.name:
                continue
            name = member.name.split(marker, 1)[1]
            if not name.startswith(("common/", "postgresql/")):
                continue
            if not name.endswith(".sql"):
                continue
            if member.size > 2 * 1024 * 1024:
                raise RuntimeError("Unexpected migration file size")
            text = tar.extractfile(member).read().decode("utf-8-sig")
            result[name] = text.replace("\r\n", "\n").replace("\r", "\n")
    if not result:
        raise RuntimeError("No migration files found")
    return result


def healthy(domain):
    result = subprocess.run([
        "curl", "-fsS", "--connect-timeout", "5", "--max-time", "20",
        "--resolve", f"{domain}:443:127.0.0.1",
        f"https://{domain}/api/health",
    ], capture_output=True, text=True)
    if result.returncode:
        return False
    try:
        health = json.loads(result.stdout)
        components = health["components"]
        return (
            health["status"] == "UP"
            and components["db"]["status"] == "UP"
            and components["ml"]["status"] == "UP"
        )
    except (ValueError, KeyError, TypeError):
        return False


def wait_healthy(domain):
    for _ in range(12):
        if healthy(domain):
            return
        time.sleep(5)
    raise RuntimeError("HTTPS/database/ML health check failed")


def main(tag):
    if os.geteuid() != 0:
        raise RuntimeError("Run this script with sudo")
    if not re.fullmatch(r"[a-f0-9]{40}-[0-9]+-[0-9]+", tag):
        raise RuntimeError("Invalid release tag")

    os.umask(0o077)
    STATE.mkdir(parents=True, exist_ok=True)
    lock = (STATE / "deploy.lock").open("a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    record = json.loads(fetch(
        f"https://github.com/{REPO}/releases/download/aws-{tag}/deployment.json",
        1024 * 1024,
    ))
    commit = record.get("commit", "")
    if (
        record.get("schema_version") != 1
        or record.get("release_tag") != tag
        or not re.fullmatch(r"[a-f0-9]{40}", commit)
        or not tag.startswith(commit + "-")
    ):
        raise RuntimeError("Invalid deployment record")

    images = record.get("images", {})
    if set(images) != {"web", "backend", "ml"}:
        raise RuntimeError("Deployment record must contain all three images")
    for name, image in images.items():
        expected = re.escape(f"{REGISTRY}/chicky-{name}@sha256:")
        if not re.fullmatch(expected + r"[a-f0-9]{64}", image):
            raise RuntimeError(f"Invalid image reference: {name}")

    # Capture the running images without reading container credentials.
    previous = {}
    for name in SERVICES:
        info = json.loads(run([
            "docker", "inspect", f"chicky-ec2-{name}-1"
        ], capture=True))[0]
        if not info["State"]["Running"]:
            raise RuntimeError(f"Existing {name} container is not running")
        previous[name] = info["Image"]

    old_image = json.loads(run([
        "docker", "image", "inspect", previous["backend"]
    ], capture=True))[0]
    old_commit = old_image["Config"].get("Labels", {}).get(
        "org.opencontainers.image.revision", ""
    )
    if not re.fullmatch(r"[a-f0-9]{40}", old_commit):
        raise RuntimeError("Cannot identify the running backend commit")
    if migrations(old_commit) != migrations(commit):
        raise RuntimeError(
            "Migration files changed. Manual database review required; "
            "nothing deployed."
        )

    attempt = STATE / f"attempt-{time.time_ns()}"
    attempt.mkdir()
    previous_refs = {}
    for name, image_id in previous.items():
        reference = f"chicky-rollback-{name}:{attempt.name}"
        run(["docker", "tag", image_id, reference])
        previous_refs[name] = reference

    rollback = attempt / "rollback.json"
    candidate = attempt / "candidate.json"
    write_json(rollback, override(previous_refs))
    target = dict(images)
    target["caddy"] = previous_refs["caddy"]
    write_json(candidate, override(target))

    resolved = json.loads(run(
        compose(candidate) + ["config", "--format", "json"], capture=True
    ))
    domain = resolved["services"]["caddy"]["environment"]["APP_DOMAIN"]
    if not re.fullmatch(r"[A-Za-z0-9.-]+", domain):
        raise RuntimeError("Invalid APP_DOMAIN")
    if not healthy(domain):
        raise RuntimeError("Existing deployment is unhealthy; nothing changed")

    for name, image in images.items():
        run(["docker", "pull", image])
        info = json.loads(run(
            ["docker", "image", "inspect", image], capture=True
        ))[0]
        revision = info["Config"].get("Labels", {}).get(
            "org.opencontainers.image.revision"
        )
        if revision != commit:
            raise RuntimeError(f"Image revision mismatch: {name}")

    try:
        run(compose(candidate) + [
            "up", "-d", "--no-build", "--pull", "never",
            "--wait", "--wait-timeout", "300",
        ])
        wait_healthy(domain)
    except Exception:
        print("Deployment failed; restoring previous images.", flush=True)
        run(compose(rollback) + [
            "up", "-d", "--no-build", "--pull", "never",
            "--wait", "--wait-timeout", "300",
        ])
        wait_healthy(domain)
        raise RuntimeError("Deployment failed; previous images restored")

    record["override_file"] = str(candidate)
    record["rollback_file"] = str(rollback)
    temporary = STATE / "current.next.json"
    with temporary.open("w") as file:
        json.dump(record, file, indent=2)
    os.replace(temporary, STATE / "current.json")
    print(f"Deployment healthy: {tag}")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise RuntimeError("Usage: chicky-deploy.py <release-tag>")
        main(sys.argv[1])
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)