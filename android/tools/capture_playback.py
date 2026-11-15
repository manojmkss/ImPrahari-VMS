"""Capture one real playback session from the test NVR's media websocket.

Run inside the throwaway backend container (cwd /app, PYTHONPATH=/app):
  python /scratch/capture_playback.py <username> <password> <out-file>

Makes three recordings for a disabled test camera - A (20 s), B (20 s, 2 s
after A: played straight across), C (20 s, 58 s after B: a real gap) - then
plays from 5 s into A and records every message until the server's "gap".

Capture format: per message, 1 byte kind (T = JSON text, B = binary),
4 bytes big-endian length, payload. The first record is a T with the test's
own facts (t0 etc.) so the Android test can check against them.
"""

import asyncio
import json
import os
import struct
import subprocess
import sys
from datetime import datetime, timezone

import httpx
import websockets
from sqlalchemy import delete, select

import app.models  # noqa: F401
from app.db.session import AsyncSessionLocal
from app.models.camera import Camera
from app.models.recording import Recording

CAM = 90
DIR = "/data/capture_test"
T0 = 1_794_000_000.0  # fixed, well in the past (2026-11-06 UTC)
PIECES = [("a", 0.0), ("b", 22.0), ("c", 100.0)]  # name, start offset from T0
LENGTH = 20.0


def naive(ts: float) -> datetime:
    return datetime.fromtimestamp(ts, timezone.utc).replace(tzinfo=None)


def make_file(path: str) -> None:
    # Video + audio, 5 fps with a keyframe every 2 s - like a camera recording,
    # finished (non-fragmented MP4, as the recorder leaves it).
    subprocess.run([
        "ffmpeg", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"testsrc2=size=160x90:rate=5:duration={LENGTH}",
        "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=16000:duration={LENGTH}",
        "-c:v", "libx264", "-g", "10", "-keyint_min", "10", "-sc_threshold", "0", "-pix_fmt", "yuv420p", "-crf", "45",
        "-c:a", "aac", "-b:a", "16k", "-shortest", "-movflags", "+faststart", path,
    ], check=True)


async def setup() -> None:
    os.makedirs(DIR, exist_ok=True)
    async with AsyncSessionLocal() as db:
        await db.execute(delete(Recording).where(Recording.camera_id == CAM))
        if (await db.execute(select(Camera).where(Camera.id == CAM))).scalar_one_or_none() is None:
            db.add(Camera(id=CAM, name="Capture test", rtsp_main_url="rtsp://127.0.0.1:1/x", enabled=False))
        for name, start in PIECES:
            path = f"{DIR}/{name}.mp4"
            make_file(path)
            db.add(Recording(camera_id=CAM, file_path=path, trigger="continuous", codec="h264",
                             started_at=naive(T0 + start), ended_at=naive(T0 + start + LENGTH),
                             duration_seconds=LENGTH, size_bytes=os.path.getsize(path)))
        await db.commit()


async def capture(user: str, password: str, out: str) -> None:
    async with httpx.AsyncClient(base_url="http://127.0.0.1:8000") as http:
        r = await http.post("/api/auth/login", json={"username": user, "password": password})
        r.raise_for_status()
        token = r.json()["access_token"]
    records = [("T", json.dumps({"facts": {"t0": T0, "pieces": PIECES, "length": LENGTH, "play_at": T0 + 5}}).encode())]
    async with websockets.connect(f"ws://127.0.0.1:8000/api/media/ws?token={token}", max_size=None) as ws:
        await ws.send(json.dumps({"op": "play", "camera": CAM, "at": T0 + 5, "audio": True}))
        # Pretend the viewer is far ahead, so the NVR never waits for "pos".
        await ws.send(json.dumps({"op": "pos", "wall": T0 + 10_000}))
        while True:
            msg = await asyncio.wait_for(ws.recv(), timeout=30)
            if isinstance(msg, str):
                records.append(("T", msg.encode()))
                kind = json.loads(msg).get("type")
                if kind in ("gap", "error"):
                    break
            else:
                records.append(("B", msg))
    with open(out, "wb") as f:
        for kind, payload in records:
            f.write(kind.encode() + struct.pack(">I", len(payload)) + payload)
    texts = [json.loads(p) for k, p in records[1:] if k == "T"]
    binaries = sum(1 for k, _ in records if k == "B")
    print(json.dumps({"texts": texts, "binary_messages": binaries, "bytes": os.path.getsize(out)}, indent=1))


async def main() -> None:
    user, password, out = sys.argv[1:4]
    await setup()
    await capture(user, password, out)


asyncio.run(main())

