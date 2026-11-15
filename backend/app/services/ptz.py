"""PTZ: pan, tilt and lens zoom of ONVIF cameras that have the motors for it.

One ONVIF session per camera, made on first use and kept (with what the
camera can do) for a while. Every call has a timeout and a session that
fails is dropped, so the next call reconnects.

Moving is "hold to move": the viewer re-sends a move every ~0.3 s while a
button is held. Each move also asks the camera to stop by itself after a
second (ONVIF `Timeout`), and the NVR stops it too after MOVE_HOLD_S without
a fresh move - so a phone that loses its connection mid-move never leaves a
camera spinning.
"""

import asyncio
import logging
import time
from dataclasses import asdict, dataclass, field
from datetime import timedelta

from app.services import ptz_state

logger = logging.getLogger(__name__)

CALL_TIMEOUT = 6.0  # one ONVIF call
CONNECT_TIMEOUT = 12.0  # connect + learn what the camera can do
SESSION_TTL = 15 * 60  # then learn its capabilities afresh
MOVE_HOLD_S = 0.8  # a move lasts this long unless renewed
MOVE_TIMEOUT = timedelta(seconds=1)  # the camera's own safety stop


class PtzError(Exception):
    def __init__(self, message: str, status: int = 502):
        super().__init__(message)
        self.status = status


@dataclass
class PtzInfo:
    supported: bool
    reason: str | None = None  # why not
    pan_tilt: bool = False
    zoom: bool = False
    presets: bool = False
    max_presets: int = 0
    home: bool = False

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class _Session:
    camera: object | None
    ptz: object | None
    profile: str | None
    info: PtzInfo
    created: float = field(default_factory=time.monotonic)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    stop_task: asyncio.Task | None = None
    timeout_ok: bool = True  # the camera accepts ContinuousMove's Timeout


_sessions: dict[int, _Session] = {}
_connecting: dict[int, asyncio.Lock] = {}


def _camera_key(cam) -> tuple:
    return (cam.onvif_address, cam.username or "", cam.password or "")


_keys: dict[int, tuple] = {}


async def _connect(cam) -> _Session:
    """ONVIF session for `cam` (a Camera row) and what its PTZ can do."""
    if not cam.onvif_address:
        return _Session(None, None, None, PtzInfo(False, "This camera was added by its stream address, not ONVIF - "
                                                         "PTZ needs ONVIF. Edit the camera and add its ONVIF address."))
    from onvif import ONVIFCamera

    from app.services.onvif_discovery import _WSDL_DIR

    host, _, port_s = cam.onvif_address.partition(":")
    port = int(port_s) if port_s.isdigit() else 80
    camera = ONVIFCamera(host, port, cam.username or "", cam.password or "", wsdl_dir=_WSDL_DIR)
    try:
        await camera.update_xaddrs()
        if "http://www.onvif.org/ver20/ptz/wsdl" not in (camera.xaddrs or {}):
            await camera.close()
            return _Session(None, None, None, PtzInfo(False, "This camera has no PTZ (no pan, tilt or zoom motors)."))
        media = await camera.create_media_service()
        profiles = await media.GetProfiles()
        profile = next((p for p in profiles or [] if getattr(p, "PTZConfiguration", None) is not None), None)
        if profile is None:
            await camera.close()
            return _Session(None, None, None, PtzInfo(False, "The camera reports PTZ, but none of its stream "
                                                             "profiles is set up for it."))
        ptz = await camera.create_ptz_service()
        info = PtzInfo(True)
        config = profile.PTZConfiguration
        node = None
        node_token = getattr(config, "NodeToken", None)
        if node_token:
            try:
                node = await ptz.GetNode({"NodeToken": node_token})
            except Exception as exc:  # some cameras don't implement GetNode
                logger.info("PTZ GetNode failed for camera %s: %s", cam.id, exc)
        if node is not None:
            spaces = getattr(node, "SupportedPTZSpaces", None)
            info.pan_tilt = bool(getattr(spaces, "ContinuousPanTiltVelocitySpace", None))
            info.zoom = bool(getattr(spaces, "ContinuousZoomVelocitySpace", None))
            info.max_presets = int(getattr(node, "MaximumNumberOfPresets", 0) or 0)
            info.home = bool(getattr(node, "HomeSupported", False))
        else:
            # No node description: go by the profile's defaults.
            info.pan_tilt = getattr(config, "DefaultContinuousPanTiltVelocitySpace", None) is not None
            info.zoom = getattr(config, "DefaultContinuousZoomVelocitySpace", None) is not None
            info.max_presets = 0
            info.home = True
        info.presets = info.max_presets > 0 or node is None
        if not (info.pan_tilt or info.zoom):
            await camera.close()
            return _Session(None, None, None, PtzInfo(False, "The camera's PTZ can't be moved continuously."))
        return _Session(camera, ptz, profile.token, info)
    except BaseException:
        try:
            await camera.close()
        except Exception:
            pass
        raise


async def _session(cam) -> _Session:
    key = _camera_key(cam)
    s = _sessions.get(cam.id)
    if s is not None and _keys.get(cam.id) == key and time.monotonic() - s.created < SESSION_TTL:
        return s
    lock = _connecting.setdefault(cam.id, asyncio.Lock())
    async with lock:
        s = _sessions.get(cam.id)
        if s is not None and _keys.get(cam.id) == key and time.monotonic() - s.created < SESSION_TTL:
            return s
        await _drop(cam.id)
        try:
            s = await asyncio.wait_for(_connect(cam), CONNECT_TIMEOUT)
        except asyncio.TimeoutError:
            raise PtzError("The camera didn't answer (ONVIF) - is it online?", 504) from None
        except PtzError:
            raise
        except Exception as exc:
            raise PtzError(_explain(exc)) from exc
        _sessions[cam.id] = s
        _keys[cam.id] = key
        return s


async def _drop(camera_id: int) -> None:
    s = _sessions.pop(camera_id, None)
    _keys.pop(camera_id, None)
    if s is None:
        return
    if s.stop_task is not None:
        s.stop_task.cancel()
    if s.camera is not None:
        try:
            await s.camera.close()
        except Exception:
            pass


def _explain(exc: BaseException) -> str:
    text = str(exc) or exc.__class__.__name__
    low = text.lower()
    if "notauthorized" in low or "not authorized" in low or "401" in low or "unauthorized" in low:
        return "The camera refused the login - check the camera's username and password."
    if "connect" in low or "timed out" in low or "unreachable" in low:
        return "Can't reach the camera's ONVIF address."
    return f"The camera refused: {text[:200]}"


async def _call(cam, what: str, fn):
    """Run `fn(session)` under the camera's lock, with a timeout; a failure
    drops the session so the next call starts fresh."""
    s = await _session(cam)
    if not s.info.supported:
        raise PtzError(s.info.reason or "This camera has no PTZ.", 409)
    try:
        async with s.lock:
            return await asyncio.wait_for(fn(s), CALL_TIMEOUT)
    except asyncio.TimeoutError:
        await _drop(cam.id)
        raise PtzError(f"The camera didn't answer ({what}).", 504) from None
    except PtzError:
        raise
    except Exception as exc:
        logger.info("PTZ %s failed on camera %s: %s", what, cam.id, exc)
        await _drop(cam.id)
        raise PtzError(_explain(exc)) from exc


# ── what callers use ─────────────────────────────────────────────────────────


async def info(cam) -> PtzInfo:
    try:
        return (await _session(cam)).info
    except PtzError as e:
        return PtzInfo(False, str(e))


def _clamp(v: float) -> float:
    return max(-1.0, min(1.0, float(v)))


async def move(cam, pan: float, tilt: float, zoom: float) -> None:
    """Move at these speeds (-1..1 each; 0 = that axis still) for MOVE_HOLD_S."""
    pan, tilt, zoom = _clamp(pan), _clamp(tilt), _clamp(zoom)

    async def go(s: _Session):
        velocity = {}
        if s.info.pan_tilt:
            velocity["PanTilt"] = {"x": pan, "y": tilt}
        if s.info.zoom:
            velocity["Zoom"] = {"x": zoom}
        request = {"ProfileToken": s.profile, "Velocity": velocity}
        if s.timeout_ok:
            try:
                await s.ptz.ContinuousMove({**request, "Timeout": MOVE_TIMEOUT})
            except Exception as exc:
                # Some cameras reject the Timeout; the NVR's own stop covers it.
                logger.info("PTZ move with Timeout refused (%s) - retrying without", exc)
                s.timeout_ok = False
                await s.ptz.ContinuousMove(request)
        else:
            await s.ptz.ContinuousMove(request)
        return s

    ptz_state.mark(cam.id, ptz_state.AFTER_MOVE)
    s = await _call(cam, "move", go)
    # Stop by ourselves unless another move renews it.
    if s.stop_task is not None:
        s.stop_task.cancel()
    s.stop_task = asyncio.create_task(_auto_stop(cam, s))


async def _auto_stop(cam, s: _Session) -> None:
    try:
        await asyncio.sleep(MOVE_HOLD_S)
    except asyncio.CancelledError:
        return
    if _sessions.get(cam.id) is not s:
        return
    s.stop_task = None
    try:
        await stop(cam)
    except PtzError as e:
        logger.warning("PTZ auto-stop failed on camera %s: %s", cam.id, e)


async def stop(cam) -> None:
    s = _sessions.get(cam.id)
    if s is not None and s.stop_task is not None and s.stop_task is not asyncio.current_task():
        s.stop_task.cancel()
        s.stop_task = None
    ptz_state.mark(cam.id, ptz_state.AFTER_MOVE)

    async def go(s: _Session):
        await s.ptz.Stop({"ProfileToken": s.profile, "PanTilt": s.info.pan_tilt, "Zoom": s.info.zoom})

    await _call(cam, "stop", go)


async def presets(cam) -> list[dict]:
    async def go(s: _Session):
        if not s.info.presets:
            return []
        rows = await s.ptz.GetPresets({"ProfileToken": s.profile})
        out = []
        for p in rows or []:
            token = getattr(p, "token", None)
            if token:
                out.append({"token": str(token), "name": getattr(p, "Name", None) or str(token)})
        return out

    return await _call(cam, "presets", go)


async def goto_preset(cam, token: str) -> None:
    async def go(s: _Session):
        await s.ptz.GotoPreset({"ProfileToken": s.profile, "PresetToken": token})

    ptz_state.mark(cam.id, ptz_state.AFTER_GOTO)
    await _call(cam, "go to preset", go)


async def save_preset(cam, name: str, token: str | None = None) -> str:
    """Save where the camera points now under `name` (or overwrite `token`)."""
    async def go(s: _Session):
        if not s.info.presets:
            raise PtzError("This camera can't store positions.", 409)
        request = {"ProfileToken": s.profile, "PresetName": name}
        if token:
            request["PresetToken"] = token
        return str(await s.ptz.SetPreset(request))

    return await _call(cam, "save preset", go)


async def remove_preset(cam, token: str) -> None:
    async def go(s: _Session):
        await s.ptz.RemovePreset({"ProfileToken": s.profile, "PresetToken": token})

    await _call(cam, "remove preset", go)


async def goto_home(cam) -> None:
    async def go(s: _Session):
        if not s.info.home:
            raise PtzError("This camera has no home position.", 409)
        await s.ptz.GotoHomePosition({"ProfileToken": s.profile})

    ptz_state.mark(cam.id, ptz_state.AFTER_GOTO)
    await _call(cam, "go home", go)


async def set_home(cam) -> None:
    async def go(s: _Session):
        if not s.info.home:
            raise PtzError("This camera has no home position.", 409)
        await s.ptz.SetHomePosition({"ProfileToken": s.profile})

    await _call(cam, "set home", go)


async def forget(camera_id: int) -> None:
    """The camera was edited or removed: reconnect next time."""
    await _drop(camera_id)


async def shutdown() -> None:
    for camera_id in list(_sessions):
        await _drop(camera_id)
