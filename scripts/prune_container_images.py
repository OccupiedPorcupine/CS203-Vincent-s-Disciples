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

    python3 scripts/prune_container_images.py --dry-run     # then without --dry-run

Runs daily from .github/workflows/prune-container-images.yml, which sets VERCEL_TOKEN. With a token
it calls Vercel's REST API directly, because the CLI looks up the token's user first, and with the
team token that failed ("User not found", 4 Nov). The token is only sent in
the Authorization header, never printed. Without a token, it goes through the signed-in Vercel CLI
(`vercel api`), for running it from a terminal. Uses only the Python standard library.
"""

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

API = "https://api.vercel.com"
TAG_LENGTH = 12


class VercelError(RuntimeError):
    pass


class Vercel:
    def __init__(self, token, scope, project):
        self.token = token
        self.scope = scope
        self.project = project
        self.repository_ids = None

    # The project and its registry repositories by ID. Listing the repositories first also gives a
    # clear error when the token can't use the registry: looking one up by name then just says "VCR
    # Repository not found" (seen on 4 Nov with a token whose role had no registry access).
    def resolve(self):
        self.project = self.request("GET", f"/v9/projects/{self.project}", project=False)["id"]
        repositories = self.request("GET", "/v1/vcr/repository")["repositories"]
        self.repository_ids = {repository["name"]: repository["id"] for repository in repositories}

    def repository_id(self, name):
        if name not in self.repository_ids:
            raise VercelError(f"No registry repository {name!r}; the token can see {sorted(self.repository_ids) or 'none'}")
        return self.repository_ids[name]

    def request(self, method, path, project=True, **params):
        query = urllib.parse.urlencode({**({"projectId": self.project} if project else {}), **params})
        if self.token:
            request = urllib.request.Request(f"{API}{path}?{query}&teamId={self.scope}", method=method,
                                             headers={"Authorization": f"Bearer {self.token}"})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    body = response.read()
            except urllib.error.HTTPError as error:
                # Vercel's error body says why (e.g. a missing permission); it never contains the token.
                raise VercelError(f"{method} {path}: HTTP {error.code} {error.read().decode('utf-8', 'replace')[:300]}")
            except urllib.error.URLError as error:
                raise VercelError(f"{method} {path}: {error.reason}")
        else:
            result = subprocess.run(["vercel", "api", f"{path}?{query}", "-X", method, "--scope", self.scope,
                                     *(["--dangerously-skip-permissions"] if method == "DELETE" else [])],
                                    capture_output=True, text=True, env={**os.environ, "VERCEL_TELEMETRY_DISABLED": "1"})
            if result.returncode != 0:
                raise VercelError(f"vercel api {method} {path} failed: {result.stderr.strip()[-300:]}")
            body = result.stdout
        return json.loads(body) if body and body.strip() else {}

    def images(self, repository):
        images, cursor = [], None
        while True:
            # The cursor parameter is "cursor"; "next" is silently ignored and returns the first page again.
            page = self.request("GET", f"/v1/vcr/repository/{self.repository_id(repository)}/images", limit=100,
                                **({"cursor": cursor} if cursor else {}))
            images += page["images"]
            cursor = page.get("nextCursor")
            if not cursor:
                return images

    def production_deployments(self, limit):
        return self.request("GET", "/v7/deployments", target="production", state="READY", limit=limit)["deployments"]

    def deployments_since(self, since_ms):
        return self.request("GET", "/v7/deployments", since=int(since_ms), limit=100)["deployments"]

    def delete(self, repository, image_id):
        self.request("DELETE", f"/v1/vcr/repository/{self.repository_id(repository)}/images/{image_id}")


def commit_tag(deployment):
    sha = (deployment.get("meta") or {}).get("githubCommitSha")
    return sha[:TAG_LENGTH] if sha else None


def tags_in_use(vercel, production_keep, recent_hours, now):
    """Image tags of the last few production deployments and of every recent deployment."""
    production = vercel.production_deployments(production_keep)
    since = (now - timedelta(hours=recent_hours)).timestamp() * 1000
    recent = [deployment for deployment in vercel.deployments_since(since)
              if (deployment.get("createdAt") or deployment.get("created")) >= since]
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
    token = os.environ.get("VERCEL_TOKEN")
    if not token:
        print("VERCEL_TOKEN isn't set, so this uses the signed-in Vercel CLI.", file=sys.stderr)

    vercel = Vercel(token, args.scope, args.project)
    try:
        vercel.resolve()
        print(f"Project {vercel.project}; registry repositories: "
              + ", ".join(f"{name} ({id_})" for name, id_ in sorted(vercel.repository_ids.items())))
        protected = tags_in_use(vercel, args.production_keep, args.recent_hours, datetime.now(timezone.utc))
    except (VercelError, KeyError, ValueError) as error:
        sys.exit(f"Couldn't look up the project, its registry or its deployments, so nothing was deleted: {error}")
    print(f"Images used by recent and production deployments: {', '.join(sorted(protected)) or 'none found'}")

    failures = 0
    for repository in args.repositories:
        try:
            images = vercel.images(repository)
        except VercelError as error:
            failures += 1
            print(f"{repository}: couldn't list images, so none were deleted: {error}", file=sys.stderr)
            continue
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
        sys.exit(f"{failures} problem(s): see above.")


if __name__ == "__main__":
    main()
