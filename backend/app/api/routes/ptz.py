"""PTZ (pan / tilt / lens zoom) of ONVIF cameras with motors.

Admins only: turning a camera changes what it records and watches, so a
viewer account (which may be a guest) can't - the same rule as the security
mode. GET tells the app whether to show the controls at all.
"""

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.deps import require_admin
from app.db.session import get_db
from app.models.camera import Camera
from app.models.user import User
from app.services import ptz
from app.services.ptz import PtzError

router = APIRouter(prefix="/api/cameras", tags=["ptz"])


class MoveIn(BaseModel):
    # Speeds, -1..1: pan right +, tilt up +, zoom in +. 0 = that axis stays.
    pan: float = Field(0.0, ge=-1, le=1)
    tilt: float = Field(0.0, ge=-1, le=1)
    zoom: float = Field(0.0, ge=-1, le=1)


class PresetIn(BaseModel):
    name: str = Field(..., min_length=1, max_length=64)


async def _camera(camera_id: int, db: AsyncSession) -> Camera:
    cam = await db.get(Camera, camera_id)
    if cam is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Camera not found")
    return cam


async def _run(coro):
    try:
        return await coro
    except PtzError as e:
        raise HTTPException(status_code=e.status, detail=str(e)) from None


@router.get("/{camera_id}/ptz")
async def get_ptz(camera_id: int, db: AsyncSession = Depends(get_db), _: User = Depends(require_admin)):
    """What this camera's PTZ can do, and its saved positions."""
    cam = await _camera(camera_id, db)
    info = await ptz.info(cam)
    out = info.to_dict()
    out["presets_list"] = []
    if info.supported and info.presets:
        try:
            out["presets_list"] = await ptz.presets(cam)
        except PtzError as e:
            out["presets_error"] = str(e)
    return out


@router.post("/{camera_id}/ptz/move", status_code=status.HTTP_204_NO_CONTENT)
async def move(camera_id: int, payload: MoveIn, db: AsyncSession = Depends(get_db), _: User = Depends(require_admin)):
    """Hold to move: lasts under a second - send again while the button is held."""
    cam = await _camera(camera_id, db)
    await _run(ptz.move(cam, payload.pan, payload.tilt, payload.zoom))


@router.post("/{camera_id}/ptz/stop", status_code=status.HTTP_204_NO_CONTENT)
async def stop(camera_id: int, db: AsyncSession = Depends(get_db), _: User = Depends(require_admin)):
    cam = await _camera(camera_id, db)
    await _run(ptz.stop(cam))


@router.post("/{camera_id}/ptz/presets")
async def save_preset(camera_id: int, payload: PresetIn, db: AsyncSession = Depends(get_db),
                      _: User = Depends(require_admin)):
    """Save where the camera points now."""
    cam = await _camera(camera_id, db)
    token = await _run(ptz.save_preset(cam, payload.name.strip()))
    return {"token": token, "name": payload.name.strip()}


@router.post("/{camera_id}/ptz/presets/{token}/goto", status_code=status.HTTP_204_NO_CONTENT)
async def goto_preset(camera_id: int, token: str, db: AsyncSession = Depends(get_db),
                      _: User = Depends(require_admin)):
    cam = await _camera(camera_id, db)
    await _run(ptz.goto_preset(cam, token))


@router.delete("/{camera_id}/ptz/presets/{token}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_preset(camera_id: int, token: str, db: AsyncSession = Depends(get_db),
                        _: User = Depends(require_admin)):
    cam = await _camera(camera_id, db)
    await _run(ptz.remove_preset(cam, token))


@router.post("/{camera_id}/ptz/home/goto", status_code=status.HTTP_204_NO_CONTENT)
async def goto_home(camera_id: int, db: AsyncSession = Depends(get_db), _: User = Depends(require_admin)):
    cam = await _camera(camera_id, db)
    await _run(ptz.goto_home(cam))


@router.post("/{camera_id}/ptz/home/set", status_code=status.HTTP_204_NO_CONTENT)
async def set_home(camera_id: int, db: AsyncSession = Depends(get_db), _: User = Depends(require_admin)):
    cam = await _camera(camera_id, db)
    await _run(ptz.set_home(cam))
