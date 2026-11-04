"""Delete old images from the project's Vercel container registry, so builds don't fail when it fills up.

Vercel keeps every image the backend and ml services build, previews included, and caps each
repository at 50. When one is full, every build fails at the image push ("repository has reached
the maximum allowed number of images"), production deploys included (issue #121). This keeps the
newest images in each repository and deletes the rest, except images a deployment still uses:

- the newest --keep images (20 by default);
- the images of the last --production-keep READY production deployments (3), so a rollback works;
- the images of any deployment created in the last --recent-hours (24), so open previews keep working.

An image's tag is the first 12 characters of the Git commit it was built from, which is how
images are matched to deployments. If the deployments can't be listed, nothing is deleted.

    VERCEL_TOKEN=... python3 scripts/prune_container_images.py --dry-run
    VERCEL_TOKEN=... python3 scripts/prune_container_images.py

Runs daily from .github/workflows/prune-container-images.yml. Uses the Vercel CLI (VERCEL_CMD,
default a pinned npx vercel) and only the Python standard library. The CLI reads the token from
VERCEL_TOKEN, so it never appears on a command line or in the log.
"""

import argparse
import json
import os
import shlex
import subprocess
import sys
from datetime import datetime, timedelta, timezone

DEFAULT_VERCEL = "npx --yes vercel@65.8.0"
TAG_LENGTH = 12


class VercelError(RuntimeError):
    pass


class Vercel:
    def __init__(self, command, scope, project):
        self.command = shlex.split(command)
        self.scope = scope
        self.project = project

    def run(self, *args):
        result = subprocess.run([*self.command, *args, "--scope", self.scope],
                                capture_output=True, text=True, env={**os.environ, "VERCEL_TELEMETRY_DISABLED": "1"})
        if result.returncode != 0:
            # The CLI's error says what went wrong (e.g. a missing or expired token); it never contains the token.
            raise VercelError(f"vercel {' '.join(args[:3])} failed: {result.stderr.strip()[-500:]}")
        return result.stdout

    def json(self, *args):
        return json.loads(self.run(*args, "--json"))

    def images(self, repository):
        images, cursor = [], None
        while True:
            page = self.json("vcr", "image", "ls", repository, "--project", self.project, "-n", "100",
                             *(["--next", cursor] if cursor else []))
            images += page["images"]
            cursor = (page.get("pagination") or {}).get("next")
            if not cursor:
                return images

    def deployments(self, *filters, limit):
        return self.json("ls", self.project, *filters, "-n", str(limit))["deployments"]

    def delete(self, repository, image_id):
        self.run("vcr", "image", "delete", repository, image_id, "--project", self.project, "--yes")


def commit_tag(deployment):
    sha = (deployment.get("meta") or {}).get("githubCommitSha")
    return sha[:TAG_LENGTH] if sha else None


def tags_in_use(vercel, production_keep, recent_hours, now):
    """Image tags of the last few production deployments and of every recent deployment."""
    production = vercel.deployments("--environment", "production", "--status", "READY", limit=production_keep)
    since = (now - timedelta(hours=recent_hours)).timestamp() * 1000
    recent = [deployment for deployment in vercel.deployments(limit=100) if deployment["createdAt"] >= since]
    return {tag for tag in map(commit_tag, production[:production_keep] + recent) if tag}


def plan(images, keep, protected_tags):
    """Splits images into (kept, deleted), newest first. Pure, so it can be tested without Vercel."""
    newest_first = sorted(images, key=lambda image: image["createdAt"], reverse=True)
    kept = newest_first[:keep]
    deleted = []
    for image in newest_first[keep:]:
        (kept if protected_tags & set(image.get("tags") or []) else deleted).append(image)
    return kept, deleted


def describe(image):
    return f"{image['id']} {','.join(image.get('tags') or ['untagged'])} {image['createdAt']}"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repositories", nargs="+", default=["backend", "ml"])
    parser.add_argument("--keep", type=int, default=20, help="newest images to keep in each repository")
    parser.add_argument("--production-keep", type=int, default=3, help="recent production deployments whose images are kept")
    parser.add_argument("--recent-hours", type=int, default=24, help="deployments newer than this keep their images")
    parser.add_argument("--project", default="chicky")
    parser.add_argument("--scope", default="team_kjkwGmp3oI9Akf8OGmoFqxDr")
    parser.add_argument("--dry-run", action="store_true", help="show what would be deleted without deleting")
    args = parser.parse_args()
    if args.keep < 1:
        parser.error("--keep must be at least 1")
    if not os.environ.get("VERCEL_TOKEN"):
        print("VERCEL_TOKEN isn't set; using the CLI's own login.", file=sys.stderr)

    vercel = Vercel(os.environ.get("VERCEL_CMD", DEFAULT_VERCEL), args.scope, args.project)
    try:
        protected = tags_in_use(vercel, args.production_keep, args.recent_hours, datetime.now(timezone.utc))
    except (VercelError, KeyError, ValueError) as error:
        sys.exit(f"Couldn't list deployments, so nothing was deleted: {error}")
    print(f"Images used by recent and production deployments: {', '.join(sorted(protected)) or 'none found'}")

    failures = 0
    for repository in args.repositories:
        images = vercel.images(repository)
        kept, deleted = plan(images, args.keep, protected)
        in_use = len(kept) - min(args.keep, len(images))
        print(f"{repository}: {len(images)} images; keeping {len(kept)} ({min(args.keep, len(images))} newest"
              f" + {in_use} in use by a deployment); {'would delete' if args.dry_run else 'deleting'} {len(deleted)}")
        for image in deleted:
            print(f"  {'would delete' if args.dry_run else 'delete'} {describe(image)}")
            if args.dry_run:
                continue
            try:
                vercel.delete(repository, image["id"])
            except VercelError as error:
                failures += 1
                print(f"  failed: {error}", file=sys.stderr)
    if failures:
        sys.exit(f"{failures} image(s) couldn't be deleted.")


if __name__ == "__main__":
    main()
