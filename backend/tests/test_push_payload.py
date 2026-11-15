"""What a push alert carries: the Android app opens playback from camera_id/at."""

import asyncio
from datetime import datetime, timezone

from app.models.event import Event
from app.services import alerts, web_push


def _sent(monkeypatch, event: Event) -> dict:
    captured = {}

    async def fake_send_all(title, body, extra=None):
        captured.update(title=title, body=body, extra=extra)

    monkeypatch.setattr(web_push, "send_push_to_all", fake_send_all)
    asyncio.run(alerts._maybe_send_push(event, "Gate", urgent=True))
    return captured


def test_push_carries_the_camera_and_the_moment_in_utc(monkeypatch):
    # DB datetimes are naive UTC: 2026-11-15 08:00:00 UTC.
    event = Event(id=7, camera_id=3, type="motion", message="Person at the gate",
                  created_at=datetime(2026, 11, 15, 8, 0, 0, 500000))
    extra = _sent(monkeypatch, event)["extra"]
    assert extra["camera_id"] == 3
    assert extra["at"] == datetime(2026, 11, 15, 8, 0, 0, 500000, tzinfo=timezone.utc).timestamp()
    # What browsers already use is unchanged.
    assert extra["url"] == "/events/7" and extra["event_id"] == 7 and extra["urgent"] is True
    assert extra["tag"] == "motion-3"
    assert "image" not in extra  # no snapshot on this event


def test_a_system_event_has_no_camera(monkeypatch):
    event = Event(id=8, camera_id=None, type="low_storage", message="Disk nearly full",
                  created_at=datetime(2026, 11, 15, 8, 0, 0, tzinfo=timezone.utc))
    extra = _sent(monkeypatch, event)["extra"]
    assert "camera_id" not in extra
    assert extra["at"] == datetime(2026, 11, 15, 8, 0, 0, tzinfo=timezone.utc).timestamp()
