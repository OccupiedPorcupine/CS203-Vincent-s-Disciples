"""Tests for scripts/prune_container_images.py's choice of images, without calling Vercel."""

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prune_container_images import plan, tags_in_use  # noqa: E402

NOW = datetime(2026, 11, 4, 12, 0, tzinfo=timezone.utc)


def image(number, tags=None):
    """Image number 0 is the oldest; tags default to a commit-like tag per image."""
    created = (NOW - timedelta(hours=100 - number)).isoformat().replace("+00:00", "Z")
    return {"id": f"image_{number}", "createdAt": created, "tags": [f"commit{number:06d}"] if tags is None else tags}


def ids(images):
    return [image["id"] for image in images]


def test_keeps_the_newest_and_deletes_the_rest_oldest_last():
    images = [image(number) for number in range(30)]

    kept, deleted = plan(images, keep=20, protected_tags=set())

    assert ids(kept) == [f"image_{number}" for number in range(29, 9, -1)]
    assert ids(deleted) == [f"image_{number}" for number in range(9, -1, -1)]


def test_deletes_nothing_when_there_are_no_more_than_keep():
    kept, deleted = plan([image(number) for number in range(20)], keep=20, protected_tags=set())

    assert len(kept) == 20
    assert deleted == []


def test_an_older_image_a_deployment_uses_is_kept_and_untagged_ones_go():
    images = [image(number) for number in range(25)] + [image(-1, tags=[])]

    kept, deleted = plan(images, keep=20, protected_tags={"commit000002"})

    assert len(kept) == 21
    assert "image_2" in ids(kept)
    assert ids(deleted) == ["image_4", "image_3", "image_1", "image_0", "image_-1"]


class FakeVercel:
    def __init__(self, production, all_deployments):
        self.production = production
        self.all_deployments = all_deployments

    def production_deployments(self, limit):
        return self.production[:limit]

    def deployments_since(self, since_ms):
        return self.all_deployments


def deployment(sha, hours_ago):
    return {"meta": {"githubCommitSha": sha}, "createdAt": (NOW - timedelta(hours=hours_ago)).timestamp() * 1000}


def test_tags_in_use_are_recent_production_and_recent_previews():
    production = [deployment("aaaaaaaaaaaa1111", 50), deployment("bbbbbbbbbbbb2222", 70),
                  deployment("cccccccccccc3333", 90), deployment("dddddddddddd4444", 110)]
    previews = [deployment("eeeeeeeeeeee5555", 2), deployment("ffffffffffff6666", 30), {"meta": {}, "createdAt": NOW.timestamp() * 1000}]

    tags = tags_in_use(FakeVercel(production, previews), production_keep=3, recent_hours=24, now=NOW)

    assert tags == {"aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc", "eeeeeeeeeeee"}
