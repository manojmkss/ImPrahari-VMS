"""Saving a clip (GET /api/recordings/export): what the web app's and the
phone app's "Save clip" ask for - real recordings, trimmed and joined."""

import asyncio
import json
import shutil
import subprocess
import time
from datetime import datetime, timezone

import pytest
from fastapi import HTTPException
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
                                  reason="ffmpeg not installed")
UTC = timezone.utc


def _segment(path, seconds, hue):
    # Like the recorder's output: fragmented MP4, a keyframe every second.
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
         "-i", f"testsrc=size=320x240:rate=10:duration={seconds},hue=h={hue}",
         "-c:v", "libx264", "-g", "10", "-movflags", "+frag_keyframe+empty_moov+default_base_moof", str(path)],
        check=True,
    )


def _probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_name",
                          "-of", "json", str(path)], capture_output=True, text=True, check=True).stdout
    info = json.loads(out)
    return float(info["format"]["duration"]), [s["codec_name"] for s in info["streams"]]


@pytest.fixture()
def nvr(tmp_path):
    import app.models  # noqa: F401
    from app.db.session import Base
    from app.models.camera import Camera
    from app.models.recording import Recording

    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path}/t.db")
    maker = async_sessionmaker(engine, expire_on_commit=False)
    t0 = round(time.time() - 3600)
    naive = lambda t: datetime.fromtimestamp(t, UTC).replace(tzinfo=None)
    # Two back-to-back 20 s recordings, then a gap.
    for i in range(2):
        _segment(tmp_path / f"seg{i}.mp4", 20, i * 90)

    async def setup():
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        async with maker() as db:
            db.add(Camera(id=1, name="Front door", rtsp_main_url="rtsp://127.0.0.1/none"))
            for i in range(2):
                db.add(Recording(camera_id=1, file_path=str(tmp_path / f"seg{i}.mp4"), trigger="continuous",
                                 started_at=naive(t0 + 20 * i), ended_at=naive(t0 + 20 * (i + 1)),
                                 duration_seconds=20, codec="h264"))
            await db.commit()

    asyncio.run(setup())
    yield t0, maker
    asyncio.run(engine.dispose())


def _export(maker, **kw):
    from app.api.routes.recordings import export_clip_download

    async def go():
        async with maker() as db:
            return await export_clip_download(db=db, _=None, **{"name": None, "h264": False, **kw})

    return asyncio.run(go())


@needs_ffmpeg
def test_a_clip_across_two_recordings_is_trimmed_to_the_range(nvr):
    t0, maker = nvr
    r = _export(maker, camera_id=1, start=t0 + 12, end=t0 + 28)
    duration, codecs = _probe(r.path)
    # Original quality is cut on keyframes (one a second): within a second of the range.
    assert 15.0 <= duration <= 17.5, duration
    assert codecs == ["h264"]
    assert "Front_door" in r.headers["content-disposition"]


@needs_ffmpeg
def test_plays_everywhere_option_is_cut_exactly(nvr):
    t0, maker = nvr
    r = _export(maker, camera_id=1, start=t0 + 12, end=t0 + 28, h264=True)
    duration, codecs = _probe(r.path)
    assert abs(duration - 16) < 0.5, duration
    assert codecs == ["h264"]


@needs_ffmpeg
def test_a_range_that_runs_into_a_gap_saves_what_was_recorded(nvr):
    t0, maker = nvr
    r = _export(maker, camera_id=1, start=t0 + 30, end=t0 + 90, h264=True)
    duration, _ = _probe(r.path)
    assert abs(duration - 10) < 0.5, duration


@needs_ffmpeg
def test_ranges_with_nothing_to_save_are_refused_clearly(nvr):
    t0, maker = nvr
    for kw, code in [
        ({"start": t0 + 100, "end": t0 + 160}, 404),  # nothing recorded then
        ({"start": t0 + 10, "end": t0 + 10}, 400),
        ({"start": t0, "end": t0 + 2 * 3600 + 1}, 400),  # over the 2-hour limit
    ]:
        with pytest.raises(HTTPException) as exc:
            _export(maker, camera_id=1, **kw)
        assert exc.value.status_code == code, kw
    with pytest.raises(HTTPException) as exc:
        _export(maker, camera_id=99, start=t0, end=t0 + 10)
    assert exc.value.status_code == 404


@pytest.fixture()
def growing(nvr, tmp_path):
    """A 15 s segment still being written (no Recording row yet), 5 s after the
    recordings end, its last frame written at t0+60."""
    import os

    from app.services.active_segment import active_segment_registry

    t0, maker = nvr
    path = tmp_path / "growing.mp4"
    _segment(path, 15, 200)
    os.utime(path, (t0 + 60, t0 + 60))
    active_segment_registry.set(1, str(path), datetime.fromtimestamp(t0 + 45, UTC))
    yield t0, maker
    active_segment_registry.clear(1)


@needs_ffmpeg
def test_the_last_minutes_still_being_recorded_can_be_saved(growing):
    t0, maker = growing
    r = _export(maker, camera_id=1, start=t0 + 48, end=t0 + 58, h264=True)
    duration, _ = _probe(r.path)
    assert abs(duration - 10) < 0.5, duration


@needs_ffmpeg
def test_a_range_from_recordings_into_the_live_segment_joins_both(growing):
    t0, maker = growing
    # 10 s of the second recording, the 5 s gap skipped, 15 s of the live segment.
    r = _export(maker, camera_id=1, start=t0 + 30, end=t0 + 60, h264=True)
    duration, _ = _probe(r.path)
    assert abs(duration - 25) < 0.7, duration
