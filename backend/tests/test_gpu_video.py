"""GPU video decoding (services/gpu_video.py): which GPU decodes, the ffmpeg
pipeline for each, what happens when the GPU fails ("GPU only" pauses, else
the CPU stands in), and the frame reader that replaced cv2.VideoCapture."""

import shutil
import subprocess

import numpy as np
import pytest

from app.services import gpu_video as gv

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")

INTEL = gv.GpuInfo(kind="vaapi", device="/dev/dri/renderD129", name="Intel GPU (i915)", codecs=["h264", "hevc"])


@pytest.fixture(autouse=True)
def fresh(monkeypatch):
    monkeypatch.setattr(gv.gpu_video, "on_broken", None)
    monkeypatch.setattr(gv.gpu_video, "on_back", None)
    yield
    gv.gpu_video.reset_for_tests(None)
    gv.gpu_video.gpu_only = True


def test_the_gpu_shrinks_and_drops_frames_before_the_cpu_sees_them():
    assert gv.hw_input_args(INTEL) == ["-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD129",
                                       "-hwaccel_output_format", "vaapi"]
    assert gv.frame_filter(INTEL, 2, 1280) == "fps=2,scale_vaapi=w=1280:h=-2:format=nv12,hwdownload,format=nv12"
    assert gv.frame_filter(INTEL, 12) == "fps=12,scale_vaapi=format=nv12,hwdownload,format=nv12"
    nvidia = gv.GpuInfo(kind="cuda", name="NVIDIA", codecs=["h264"])
    assert gv.hw_input_args(nvidia)[:2] == ["-hwaccel", "cuda"]
    assert gv.frame_filter(nvidia, 2, 640, 360) == "fps=2,scale_cuda=w=640:h=360:format=nv12,hwdownload,format=nv12"
    cpu = gv.GpuInfo()
    assert gv.hw_input_args(cpu) == [] and gv.frame_filter(cpu, 2, 320) == "fps=2,scale=320:-2"
    assert gv.frame_filter(cpu, None) == "null"


def test_a_virtual_display_is_skipped_and_the_real_gpu_is_proven(monkeypatch):
    # A VMware VM with the Intel GPU passed through: renderD128 is VMware's
    # display, renderD129 the GPU.
    monkeypatch.delenv("IMPRAHARI_GPU_DEVICE", raising=False)
    monkeypatch.setattr(gv, "_render_nodes", lambda: ["/dev/dri/renderD128", "/dev/dri/renderD129"])
    vendor = {"renderD128": "0x15ad", "renderD129": "0x8086"}
    monkeypatch.setattr(gv, "_sys_attr", lambda node, a: vendor[node.rsplit("/", 1)[1]] if a == "vendor" else "i915")
    probed = []
    monkeypatch.setattr(gv, "_probe", lambda info: (probed.append(info.device) or (["h264", "hevc"], "")))
    info = gv.detect()
    assert info.kind == "vaapi" and info.device == "/dev/dri/renderD129" and info.name == "Intel GPU (i915)"
    assert probed == ["/dev/dri/renderD129"]
    assert info.tried[0]["device"] == "/dev/dri/renderD128" and "virtual display" in info.tried[0]["error"]


def test_no_gpu_or_a_gpu_that_cannot_decode_says_why(monkeypatch):
    monkeypatch.delenv("IMPRAHARI_GPU_DEVICE", raising=False)
    monkeypatch.setattr(gv.os.path, "exists", lambda p: False if p == "/dev/nvidiactl" else True)
    monkeypatch.setattr(gv, "_render_nodes", lambda: [])
    info = gv.detect()
    assert info.kind == "cpu" and "No GPU is connected" in info.reason
    monkeypatch.setattr(gv, "_render_nodes", lambda: ["/dev/dri/renderD128"])
    monkeypatch.setattr(gv, "_sys_attr", lambda node, a: "0x1002" if a == "vendor" else "amdgpu")
    monkeypatch.setattr(gv, "_probe", lambda info: ([], "Failed to initialise VAAPI connection"))
    info = gv.detect()
    assert info.kind == "cpu" and "couldn't decode video" in info.reason and "AMD GPU" in info.reason
    monkeypatch.setenv("IMPRAHARI_GPU_DEVICE", "cpu")
    assert gv.detect().kind == "cpu"


def test_gpu_only_pauses_instead_of_using_the_cpu_and_retries(monkeypatch):
    v = gv.gpu_video
    v.reset_for_tests(INTEL)
    alerts, back = [], []
    v.on_broken, v.on_back = alerts.append, back.append
    assert v.plan() is INTEL
    for _ in range(gv.FAILS_BEFORE_BROKEN):
        v.failed("Failed to sync surface")
    assert v.broken and alerts == ["Failed to sync surface"]
    monkeypatch.setattr(gv, "detect", lambda: gv.GpuInfo(reason="still broken"))
    assert v.plan() is None and v.status()["state"] == "paused"  # never the CPU
    v.gpu_only = False
    v._next_retry = 0
    stand_in = v.plan()
    assert stand_in.kind == "cpu" and "stopped working" in stand_in.reason
    # It comes back: the next retry finds it and says so.
    monkeypatch.setattr(gv, "detect", lambda: INTEL)
    v._next_retry = 0
    assert v.plan() is INTEL and not v.broken and back == ["Intel GPU (i915)"]


def test_a_working_stream_resets_the_failure_count():
    v = gv.gpu_video
    v.reset_for_tests(INTEL)
    v.failed("x")
    v.failed("x")
    v.ok()
    v.failed("x")
    assert not v.broken


def test_without_a_gpu_the_cpu_is_used_whatever_gpu_only_says():
    gv.gpu_video.reset_for_tests(gv.GpuInfo(reason="No GPU is connected"))
    gv.gpu_video.gpu_only = True
    assert gv.gpu_video.plan().kind == "cpu" and gv.gpu_video.status()["state"] == "cpu"


def test_a_gpu_without_the_cameras_codec_leaves_that_camera_to_the_cpu(monkeypatch):
    old = gv.GpuInfo(kind="vaapi", device="/dev/dri/renderD128", name="Intel GPU (i915)", codecs=["h264"])
    gv.gpu_video.reset_for_tests(old)
    monkeypatch.setattr(gv.subprocess, "Popen", lambda *a, **k: (_ for _ in ()).throw(OSError("not run")))
    r = gv.FrameReader("rtsp://cam/main", fps=2, codec="h265")
    assert r.info.kind == "cpu" and "can't decode hevc" in r.info.reason
    assert gv.FrameReader("rtsp://cam/main", fps=2, codec="h264").info is old


def test_gpu_errors_are_told_from_stream_errors():
    assert gv.looks_like_gpu_error("[AVHWFramesContext] Failed to sync surface 0x8")
    assert gv.looks_like_gpu_error("Device creation failed: -5.")
    assert not gv.looks_like_gpu_error("rtsp://cam: Connection refused")
    assert not gv.looks_like_gpu_error("")


def _clip(path, seconds=3, size="640x360"):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                    f"testsrc=size={size}:rate=25:duration={seconds}", "-c:v", "libx264", "-preset", "ultrafast",
                    "-pix_fmt", "yuv420p", "-y", str(path)], check=True)


@needs_ffmpeg
def test_the_reader_hands_over_bgr_pictures_at_the_rate_asked(tmp_path):
    clip = tmp_path / "c.mp4"
    _clip(clip)
    r = gv.FrameReader(str(clip), fps=5, width=320, info=gv.GpuInfo())
    assert r.isOpened() and (r.width, r.height) == (320, 180)
    frames = []
    while True:
        ok, f = r.read()
        if not ok:
            break
        frames.append(f)
    r.release()
    assert 14 <= len(frames) <= 16
    assert frames[0].shape == (180, 320, 3) and frames[0].dtype == np.uint8
    frames[0][0, 0] = 1  # writable, like cv2's frames (drawing boxes on them)
    assert not r.error


@needs_ffmpeg
def test_a_reader_that_cannot_open_says_so_and_a_gpu_error_counts(tmp_path):
    bad = gv.FrameReader(str(tmp_path / "missing.mp4"), fps=2, info=gv.GpuInfo())
    assert not bad.isOpened(timeout=10) and bad.error
    bad.release()
    gv.gpu_video.reset_for_tests(INTEL)
    clip = tmp_path / "c.mp4"
    _clip(clip, seconds=1)
    # A render node that isn't there: ffmpeg fails to create the device.
    ghost = gv.GpuInfo(kind="vaapi", device=str(tmp_path / "renderD999"), name="ghost", codecs=["h264", "hevc"])
    r = gv.FrameReader(str(clip), fps=2, info=ghost)
    assert not r.isOpened(timeout=10)
    r.release()
    assert gv.gpu_video._fails == 1


def test_paused_reader_opens_nothing():
    gv.gpu_video.reset_for_tests(INTEL)
    gv.gpu_video.broken = True
    gv.gpu_video._next_retry = 9e18
    r = gv.FrameReader("rtsp://cam/sub", fps=12)
    assert r.paused and not r.isOpened(timeout=0.1)
    r.release()
