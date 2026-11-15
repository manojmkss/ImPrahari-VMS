"""Which cameras are being turned or zoomed by PTZ right now.

Kept apart from services/ptz.py (which imports ONVIF) so the decode loop can
ask cheaply, from its own thread: while a camera moves on purpose, the whole
picture changes - that is neither motion nor tampering, and the detectors
must start again from the new view once it settles.
"""

import threading
import time

# Seconds the picture is treated as "moving" after each kind of command.
AFTER_MOVE = 3.0  # a held move: re-sent every ~0.3 s, plus settling
AFTER_GOTO = 10.0  # a preset/home: the camera travels on its own, often several seconds

_until: dict[int, float] = {}
_lock = threading.Lock()


def mark(camera_id: int, seconds: float) -> None:
    with _lock:
        _until[camera_id] = max(_until.get(camera_id, 0.0), time.monotonic() + seconds)


def moving(camera_id: int) -> bool:
    until = _until.get(camera_id)
    return until is not None and time.monotonic() < until


def clear() -> None:
    with _lock:
        _until.clear()
