"""PTZ against a fake ONVIF camera speaking real SOAP (tests/onvif_fake.py)."""

import asyncio
import re
import time
from types import SimpleNamespace

import pytest

from app.services import ptz, ptz_state
from app.services.ptz import PtzError
from tests.onvif_fake import FakePtzCamera, _find


def cam_for(fake: FakePtzCamera, cid: int = 1, password: str = "secret"):
    return SimpleNamespace(id=cid, onvif_address=f"127.0.0.1:{fake.port}", username="admin", password=password)


def run(coro):
    async def go():
        try:
            return await coro
        finally:
            await ptz.shutdown()
    return asyncio.run(go())


def _velocity(el):
    pt = _find(el, "PanTilt")
    zm = _find(el, "Zoom")
    return ({k: float(v) for k, v in pt.attrib.items()} if pt is not None else None,
            {k: float(v) for k, v in zm.attrib.items()} if zm is not None else None)


def setup_function():
    ptz_state.clear()


def test_learns_what_the_camera_can_do():
    with FakePtzCamera(max_presets=8, home=True) as fake:
        info = run(ptz.info(cam_for(fake)))
    assert info.supported and info.pan_tilt and info.zoom and info.presets and info.home
    assert info.max_presets == 8
    assert fake.actions()[:3] == ["GetCapabilities", "GetProfiles", "GetNode"]


def test_a_fixed_camera_says_so():
    with FakePtzCamera(ptz=False) as fake:
        info = run(ptz.info(cam_for(fake)))
    assert not info.supported and "no PTZ" in info.reason


def test_a_camera_without_onvif_says_how_to_fix_it():
    info = run(ptz.info(SimpleNamespace(id=9, onvif_address=None, username="", password="")))
    assert not info.supported and "ONVIF" in info.reason


def test_zoom_only_camera_sends_only_zoom():
    with FakePtzCamera(pan_tilt=False, zoom=True) as fake:
        async def go():
            c = cam_for(fake)
            await ptz.move(c, 0.5, 0.5, 1.0)
        run(go())
        pan_tilt, zoom = _velocity(fake.last("ContinuousMove"))
    assert pan_tilt is None and zoom == {"x": 1.0}


def test_a_move_carries_the_speeds_a_timeout_and_stops_by_itself():
    with FakePtzCamera() as fake:
        async def go():
            c = cam_for(fake)
            await ptz.move(c, 0.5, -0.25, 0.0)
            assert ptz_state.moving(c.id)  # motion/tamper pause while it turns
            await asyncio.sleep(ptz.MOVE_HOLD_S + 0.5)
        run(go())
        el = fake.last("ContinuousMove")
        pan_tilt, zoom = _velocity(el)
        assert pan_tilt == {"x": 0.5, "y": -0.25} and zoom == {"x": 0.0}
        assert re.fullmatch(r"P(T)?1(\.0+)?S|PT1S", _find(el, "Timeout").text)
        assert _find(el, "ProfileToken").text == "main"  # the profile set up for PTZ
        assert fake.actions()[-1] == "Stop"  # the NVR's own safety stop


def test_holding_a_button_renews_the_move_without_stopping_in_between():
    with FakePtzCamera() as fake:
        async def go():
            c = cam_for(fake)
            for _ in range(5):  # what the apps send while a button is held
                await ptz.move(c, 1, 0, 0)
                await asyncio.sleep(0.3)
            assert "Stop" not in fake.actions()
            await ptz.stop(c)  # released
            await asyncio.sleep(ptz.MOVE_HOLD_S + 0.3)
        run(go())
        acts = fake.actions()
        assert acts.count("ContinuousMove") == 5
        assert acts.count("Stop") == 1  # the release; the cancelled auto-stop sends nothing


def test_a_camera_that_rejects_the_timeout_still_moves():
    with FakePtzCamera(reject_timeout=True) as fake:
        async def go():
            c = cam_for(fake)
            await ptz.move(c, 1, 0, 0)
            await ptz.move(c, 1, 0, 0)
            await ptz.stop(c)
        run(go())
        moves = [b for a, b in fake.calls if a == "ContinuousMove"]
    # Tried with Timeout once, then without - and remembers that.
    assert [_find(m, "Timeout") is not None for m in moves] == [True, False, False]


def test_presets_save_go_and_remove():
    with FakePtzCamera() as fake:
        async def go():
            c = cam_for(fake)
            before = await ptz.presets(c)
            token = await ptz.save_preset(c, "Porch")
            during = await ptz.presets(c)
            await ptz.goto_preset(c, token)
            assert ptz_state.moving(c.id)
            await ptz.remove_preset(c, token)
            after = await ptz.presets(c)
            return before, token, during, after
        before, token, during, after = run(go())
    assert before == [{"token": "1", "name": "Gate"}]
    assert {"token": token, "name": "Porch"} in during
    assert after == before
    assert _find(fake.last("GotoPreset"), "PresetToken").text == token


def test_home_position():
    with FakePtzCamera() as fake:
        async def go():
            c = cam_for(fake)
            await ptz.set_home(c)
            await ptz.goto_home(c)
        run(go())
    assert fake.actions()[-2:] == ["SetHomePosition", "GotoHomePosition"]


def test_wrong_password_is_said_plainly():
    with FakePtzCamera(password="secret") as fake:
        info = run(ptz.info(cam_for(fake, password="wrong")))
    assert not info.supported and "login" in info.reason


def test_a_camera_that_hangs_times_out_and_the_next_call_reconnects(monkeypatch):
    monkeypatch.setattr(ptz, "CALL_TIMEOUT", 1.0)
    with FakePtzCamera(hang={"GotoHomePosition"}) as fake:
        async def go():
            c = cam_for(fake)
            started = time.monotonic()
            with pytest.raises(PtzError) as e:
                await ptz.goto_home(c)
            took = time.monotonic() - started
            fake.hang.clear()
            await ptz.goto_home(c)  # works again, on a fresh session
            return e.value, took
        err, took = run(go())
    assert err.status == 504 and took < 3
    assert fake.actions().count("GetCapabilities") == 2


def test_unknown_preset_is_an_error_not_a_crash():
    with FakePtzCamera() as fake:
        async def go():
            with pytest.raises(PtzError):
                await ptz.goto_preset(cam_for(fake), "nope")
        run(go())


def test_moving_pauses_motion_and_tamper_then_learns_the_new_view():
    """The decode loop's view: while PTZ moves, neither detector runs, and
    both start over from the new picture."""
    import numpy as np

    from app.services.motion_detector import MotionDetector
    from app.services.tamper import TamperDetector

    det, tam = MotionDetector(), TamperDetector()
    frame = np.zeros((360, 640, 3), np.uint8)
    for i in range(30):
        det.process(frame, i / 6)
        tam.check(frame, i * 5.0)
    det.active = True
    ptz_state.mark(1, 0.2)
    assert ptz_state.moving(1)
    # What stream_viewer does for each frame while moving:
    assert det.force_stop() is True
    det.reset(100.0)
    tam.relearn()
    assert det.active is False and tam._samples == 0
    time.sleep(0.25)
    assert not ptz_state.moving(1)


def _client(tmp_path, role: str, fake: FakePtzCamera | None):
    """The real PTZ routes, a throwaway DB with one camera, and a signed-in user."""
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

    import app.models  # noqa: F401
    from app.api.routes import ptz as routes
    from app.core.deps import get_current_user
    from app.db.session import Base, get_db
    from app.models.camera import Camera

    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path}/t.db")
    maker = async_sessionmaker(engine, expire_on_commit=False)

    async def setup():
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        async with maker() as db:
            db.add(Camera(id=1, name="Gate", rtsp_main_url="rtsp://x", enabled=False, username="admin",
                          password="secret", onvif_address=f"127.0.0.1:{fake.port}" if fake else None))
            await db.commit()

    asyncio.run(setup())

    async def db_dep():
        async with maker() as db:
            yield db

    app = FastAPI()
    app.include_router(routes.router)
    app.dependency_overrides[get_db] = db_dep
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=1, username="u", role=role)
    return TestClient(app)


def test_routes_for_an_admin(tmp_path):
    with FakePtzCamera() as fake:
        with _client(tmp_path, "admin", fake) as c:
            got = c.get("/api/cameras/1/ptz").json()
            assert got["supported"] and got["presets_list"] == [{"token": "1", "name": "Gate"}]
            assert c.post("/api/cameras/1/ptz/move", json={"pan": 0.5, "tilt": 0, "zoom": 0}).status_code == 204
            assert c.post("/api/cameras/1/ptz/move", json={"pan": 5}).status_code == 422  # out of range
            assert c.post("/api/cameras/1/ptz/stop").status_code == 204
            saved = c.post("/api/cameras/1/ptz/presets", json={"name": "Porch"}).json()
            assert c.post(f"/api/cameras/1/ptz/presets/{saved['token']}/goto").status_code == 204
            assert c.delete(f"/api/cameras/1/ptz/presets/{saved['token']}").status_code == 204
            assert c.post("/api/cameras/1/ptz/home/goto").status_code == 204
            assert c.get("/api/cameras/99/ptz").status_code == 404
            c.portal.call(ptz.shutdown)


def test_a_viewer_cant_turn_cameras(tmp_path):
    with FakePtzCamera() as fake:
        with _client(tmp_path, "viewer", fake) as c:
            assert c.get("/api/cameras/1/ptz").status_code == 403
            assert c.post("/api/cameras/1/ptz/move", json={"pan": 1}).status_code == 403
        assert fake.actions() == []  # the camera was never even contacted


def test_a_camera_without_ptz_answers_409_on_move(tmp_path):
    with FakePtzCamera(ptz=False) as fake:
        with _client(tmp_path, "admin", fake) as c:
            assert c.get("/api/cameras/1/ptz").json()["supported"] is False
            r = c.post("/api/cameras/1/ptz/move", json={"pan": 1})
            assert r.status_code == 409 and "no PTZ" in r.json()["detail"]
            c.portal.call(ptz.shutdown)
