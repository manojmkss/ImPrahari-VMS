import asyncio
import os


async def generate_thumbnail(video_path: str, at_seconds: float = 0.5) -> str | None:
    """Writes the thumbnail next to the video (same dir, same basename, .jpg)
    so the storage mover can move/copy a recording's pair of files as one
    unit instead of tracking a separate thumbnail tree per tier.

    `at_seconds`: motion clips pass the moment motion was detected - with
    pre-roll, the first half-second is usually an empty scene.

    Decoded and shrunk on the GPU when there is one (services/gpu_video.py);
    none while "GPU only" waits for a GPU that stopped working.
    """
    from app.services.gpu_video import frame_filter, gpu_video, hw_input_args, looks_like_gpu_error

    info = await asyncio.to_thread(gpu_video.plan)
    if info is None:
        return None
    thumb_path = os.path.splitext(video_path)[0] + ".jpg"

    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-nostdin",
        *hw_input_args(info),
        "-ss", f"{max(0.0, at_seconds):.2f}",
        "-i", video_path,
        "-frames:v", "1",
        "-vf", frame_filter(info, None, 320),
        thumb_path,
    ]
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE
    )
    try:
        # Bounded: a corrupt file can make ffmpeg spin, and saving the
        # recording (and freeing its buffer segments) waits on this.
        _, err = await asyncio.wait_for(proc.communicate(), timeout=30)
    except asyncio.TimeoutError:
        proc.kill()
        await proc.wait()
        return None
    if proc.returncode != 0 and info.kind != "cpu":
        text = (err or b"").decode(errors="ignore")
        if looks_like_gpu_error(text):
            gpu_video.failed(text)
    return thumb_path if proc.returncode == 0 and os.path.exists(thumb_path) else None
