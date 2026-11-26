"""Video decoding on the GPU, by design.

Every picture the NVR looks at (the live checks, motion, the closer look at
a person, the visit review, thumbnails) is decoded here, by ffmpeg, on the
machine's GPU when it has one:

- Intel and AMD GPUs through VAAPI (/dev/dri/renderD*), NVIDIA GPUs through
  CUDA/NVDEC. Which one is found out at startup by actually decoding a test
  clip on it - never assumed from a device name - so a VM's virtual display,
  a GPU without the needed codec or a missing driver are skipped.
- The GPU also shrinks the picture and drops the frames nobody needs before
  the result reaches the CPU, so the backend only receives finished pictures
  at the rate they are used.
- Recording, live view, playback and clip downloads never decode at all:
  they copy the camera's own H.264/H.265 as it is.

Without a GPU (a Raspberry Pi, a VM without one passed through) the same
ffmpeg pipeline runs on the CPU. With a GPU found, "GPU only" (Settings ->
System -> Media processing, on by default) means the CPU is never used
instead: if the GPU stops working, motion and AI checks pause (recording
carries on), an alert is sent, and the GPU is tried again every minute.

IMPRAHARI_GPU_DEVICE overrides the choice: a render node
(/dev/dri/renderD129), "cuda", or "cpu" to never use a GPU.
"""

from __future__ import annotations

import glob
import logging
import os
import re
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass, field

import numpy as np

logger = logging.getLogger(__name__)

PROBE_TIMEOUT = 20
RETRY_SECONDS = 60.0
FAILS_BEFORE_BROKEN = 3  # decoder failures in a row before the GPU counts as broken
OPEN_TIMEOUT = 25.0  # a stream that hasn't started delivering in this long failed to open

# PCI vendor ids (sysfs) - for naming the GPU only; the probe decides.
VENDORS = {"0x8086": "Intel", "0x1002": "AMD", "0x10de": "NVIDIA", "0x15ad": "VMware", "0x1af4": "virtio",
           "0x1234": "QEMU", "0x1414": "Microsoft"}
# Virtual displays that never decode video: not worth probing.
SKIP_VENDORS = {"0x15ad", "0x1234"}


@dataclass
class GpuInfo:
    kind: str = "cpu"  # vaapi | cuda | cpu
    device: str | None = None  # render node for vaapi
    name: str = "CPU"
    codecs: list[str] = field(default_factory=list)  # decoders proven on it: h264, hevc
    reason: str = ""  # why the CPU is used (when kind == cpu)
    tried: list[dict] = field(default_factory=list)  # every device probed, and how it went

    def to_dict(self) -> dict:
        return {"kind": self.kind, "device": self.device, "name": self.name, "codecs": self.codecs,
                "reason": self.reason, "tried": self.tried}


def _sys_attr(node: str, attr: str) -> str:
    base = os.path.basename(node)
    try:
        if attr == "driver":
            return os.path.basename(os.path.realpath(f"/sys/class/drm/{base}/device/driver"))
        with open(f"/sys/class/drm/{base}/device/{attr}", encoding="ascii") as f:
            return f.read().strip()
    except OSError:
        return ""


def _render_nodes() -> list[str]:
    return sorted(glob.glob("/dev/dri/renderD*"))


def _run(args: list[str], timeout: float = PROBE_TIMEOUT) -> tuple[bool, str]:
    try:
        p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", *args],
                           stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=timeout)
        return p.returncode == 0, p.stderr.decode(errors="ignore").strip()[-300:]
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, str(exc)[:300]


_samples: dict[str, str] = {}


def _sample(codec: str) -> str | None:
    """A tiny clip in `codec` (made once, on the CPU) to prove the GPU can
    decode it."""
    if codec in _samples and os.path.exists(_samples[codec]):
        return _samples[codec]
    enc = {"h264": "libx264", "hevc": "libx265"}[codec]
    path = os.path.join(tempfile.gettempdir(), f"imprahari_gpu_probe_{codec}.mp4")
    ok, _ = _run(["-y", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=10:d=1", "-c:v", enc,
                  "-pix_fmt", "yuv420p", *(["-x265-params", "log-level=error"] if codec == "hevc" else []),
                  path])
    if not ok:
        return None
    _samples[codec] = path
    return path


def hw_input_args(info: GpuInfo) -> list[str]:
    if info.kind == "vaapi":
        return ["-hwaccel", "vaapi", "-hwaccel_device", info.device, "-hwaccel_output_format", "vaapi"]
    if info.kind == "cuda":
        return ["-hwaccel", "cuda", "-hwaccel_output_format", "cuda"]
    return []


def frame_filter(info: GpuInfo, fps: float | None, width: int | None = None, height: int | None = None) -> str:
    """The -vf chain: drop to `fps`, scale (on the GPU when there is one) and
    hand over NV12 to be packed into BGR for OpenCV. width/height None keeps
    the camera's own size; -2 keeps the aspect ratio."""
    parts = [f"fps={fps:g}"] if fps else []
    size = f"w={width}:h={height if height else -2}" if width else ""
    if info.kind == "vaapi":
        parts.append(f"scale_vaapi={size + ':' if size else ''}format=nv12")
        parts += ["hwdownload", "format=nv12"]
    elif info.kind == "cuda":
        parts.append(f"scale_cuda={size + ':' if size else ''}format=nv12")
        parts += ["hwdownload", "format=nv12"]
    elif width:
        parts.append(f"scale={width}:{height if height else -2}")
    return ",".join(parts) or "null"


def _probe(info: GpuInfo) -> tuple[list[str], str]:
    """Decode a test clip per codec on `info`'s device; returns the codecs
    that worked and the last error."""
    codecs, err = [], ""
    for codec in ("h264", "hevc"):
        clip = _sample(codec)
        if clip is None:
            continue
        ok, msg = _run([*hw_input_args(info), "-i", clip, "-vf", frame_filter(info, 5, 320),
                        "-pix_fmt", "bgr24", "-f", "rawvideo", "-y", os.devnull])
        if ok:
            codecs.append(codec)
        else:
            err = msg
    return codecs, err


def _gpu_name(node: str) -> str:
    vendor = VENDORS.get(_sys_attr(node, "vendor"), "")
    driver = _sys_attr(node, "driver")
    label = f"{vendor} GPU" if vendor else "GPU"
    return f"{label} ({driver})" if driver and driver != "device" else label


def detect() -> GpuInfo:
    """Find the GPU that decodes video in this container (see module doc)."""
    override = os.environ.get("IMPRAHARI_GPU_DEVICE", "").strip()
    if override.lower() == "cpu":
        return GpuInfo(reason="IMPRAHARI_GPU_DEVICE=cpu: the GPU is switched off for video")
    tried: list[dict] = []
    nodes = [override] if override.startswith("/dev/") else _render_nodes()
    if override.lower() != "cuda":
        for node in nodes:
            if not override and _sys_attr(node, "vendor") in SKIP_VENDORS:
                tried.append({"device": node, "name": _gpu_name(node), "ok": False,
                              "error": "a virtual display, not a video decoder"})
                continue
            info = GpuInfo(kind="vaapi", device=node, name=_gpu_name(node))
            codecs, err = _probe(info)
            tried.append({"device": node, "name": info.name, "ok": bool(codecs), "codecs": codecs,
                          "error": "" if codecs else err})
            if codecs:
                info.codecs, info.tried = codecs, tried
                return info
    if override.lower() == "cuda" or os.path.exists("/dev/nvidiactl"):
        info = GpuInfo(kind="cuda", name="NVIDIA GPU (CUDA)")
        codecs, err = _probe(info)
        tried.append({"device": "cuda", "name": info.name, "ok": bool(codecs), "codecs": codecs,
                      "error": "" if codecs else err})
        if codecs:
            info.codecs, info.tried = codecs, tried
            return info
    if not tried:
        reason = ("No GPU is connected to the NVR. If this machine has one, run the update (it connects it); "
                  "on a VM, pass the GPU through first.")
    else:
        reason = "A GPU was found but couldn't decode video: " + "; ".join(
            f"{t['name']}: {t['error'] or 'failed'}" for t in tried)
    return GpuInfo(reason=reason[:600], tried=tried)


class GpuVideo:
    """The NVR-wide decoder choice, and what happens when the GPU fails."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._info: GpuInfo | None = None
        self.gpu_only = True  # Settings -> System -> Media processing
        self.found_gpu = False  # a GPU worked at some point since startup
        self.broken = False  # the GPU stopped working (failed FAILS_BEFORE_BROKEN times)
        self.broken_reason = ""
        self._fails = 0
        self._next_retry = 0.0
        self.on_broken = None  # callable(reason) - sends the alert (set by main)
        self.on_back = None

    @property
    def info(self) -> GpuInfo:
        with self._lock:
            if self._info is None:
                self._info = detect()
                self.found_gpu = self.found_gpu or self._info.kind != "cpu"
                if self._info.kind == "cpu":
                    logger.warning("Video decoding on the CPU: %s", self._info.reason)
                else:
                    logger.info("Video decoding on the GPU: %s %s (%s)", self._info.name,
                                self._info.device or "", ", ".join(self._info.codecs))
            return self._info

    def plan(self) -> GpuInfo | None:
        """The decoder to use now: the GPU, the CPU, or None = paused (GPU
        only and the GPU is broken)."""
        info = self.info
        if info.kind == "cpu":
            if self.found_gpu and self.gpu_only:
                return None  # had a GPU, lost it: don't quietly fall back
            return info
        if self.broken:
            if time.monotonic() >= self._next_retry:
                self._retry()
            if self.broken:
                return None if self.gpu_only else GpuInfo(reason=f"the GPU stopped working: {self.broken_reason}")
        return info

    def _retry(self) -> None:
        self._next_retry = time.monotonic() + RETRY_SECONDS
        fresh = detect()
        if fresh.kind != "cpu":
            with self._lock:
                self._info, self.broken, self._fails = fresh, False, 0
            logger.info("The GPU decodes video again: %s", fresh.name)
            if self.on_back:
                self.on_back(fresh.name)

    def ok(self) -> None:
        self._fails = 0

    def failed(self, reason: str) -> None:
        """A GPU decode failed (stream drops are not failures - callers only
        report errors that point at the decoder)."""
        if self.info.kind == "cpu" or self.broken:
            return
        self._fails += 1
        logger.warning("GPU decode failed (%d/%d): %s", self._fails, FAILS_BEFORE_BROKEN, reason[:200])
        if self._fails >= FAILS_BEFORE_BROKEN:
            self.broken, self.broken_reason = True, reason[:200]
            self._next_retry = time.monotonic() + RETRY_SECONDS
            logger.error("The GPU stopped decoding video (%s) - %s", reason[:200],
                         "motion and AI checks pause until it works again" if self.gpu_only else "using the CPU")
            if self.on_broken:
                self.on_broken(self.broken_reason)

    def status(self) -> dict:
        info = self.info
        plan = self.plan()
        if plan is None:
            state = "paused"
        elif plan.kind == "cpu":
            state = "cpu"
        else:
            state = "gpu"
        return {**info.to_dict(), "state": state, "gpu_only": self.gpu_only, "broken": self.broken,
                "broken_reason": self.broken_reason}

    def reset_for_tests(self, info: GpuInfo | None = None) -> None:
        with self._lock:
            self._info = info
            self.found_gpu = bool(info and info.kind != "cpu")
            self.broken, self.broken_reason, self._fails, self._next_retry = False, "", 0, 0.0


gpu_video = GpuVideo()


_HW_ERROR = re.compile(r"vaapi|cuda|hwaccel|hwdownload|hwupload|surface|device creation|libva|nvdec|cuvid",
                       re.IGNORECASE)


def looks_like_gpu_error(stderr: str) -> bool:
    return bool(_HW_ERROR.search(stderr or ""))


# ── reading frames ──────────────────────────────────────────────────────────

_SIZE = re.compile(r"Stream #0:\d+.*?: Video: rawvideo.*?bgr24.*?, (\d{2,5})x(\d{2,5})")


class FrameReader:
    """Pictures from a stream or a file, decoded by ffmpeg (on the GPU when
    there is one) at `fps`, as BGR numpy arrays - a drop-in for the bits of
    cv2.VideoCapture the NVR used (isOpened / read / release), safe to read
    from a background thread.

    `info` None = gpu_video's current choice; a plan of None (paused) leaves
    the reader closed with `paused` set.
    """

    def __init__(self, source: str, fps: float | None, width: int | None = None, height: int | None = None,
                 start: float | None = None, duration: float | None = None, info: GpuInfo | None = None,
                 threads: int | None = None, codec: str | None = None):
        self.source = source
        self.paused = False
        self.width = self.height = 0
        self.error = ""
        self._proc: subprocess.Popen | None = None
        self._ready = threading.Event()
        self._lines: list[str] = []
        self.info = info if info is not None else gpu_video.plan()
        if self.info is None:
            self.paused = True
            return
        codec = {"h265": "hevc", "avc": "h264"}.get((codec or "").lower(), (codec or "").lower())
        if self.info.kind != "cpu" and codec in ("h264", "hevc") and codec not in self.info.codecs:
            # This GPU can't decode the camera's codec (an older GPU and
            # H.265): the CPU has to, whatever "GPU only" says.
            self.info = GpuInfo(reason=f"{gpu_video.info.name} can't decode {codec}")
        args = ["ffmpeg", "-hide_banner", "-loglevel", "info", "-nostats", "-nostdin"]
        if source.startswith(("rtsp://", "rtsps://")):
            args += ["-rtsp_transport", "tcp", "-timeout", "10000000"]
        if threads:
            args += ["-threads", str(threads)]
        args += hw_input_args(self.info)
        if start:
            args += ["-ss", f"{start:.3f}"]
        args += ["-i", source]
        if duration:
            args += ["-t", f"{duration:.3f}"]
        args += ["-map", "0:v:0", "-an", "-vf", frame_filter(self.info, fps, width, height),
                 "-pix_fmt", "bgr24", "-f", "rawvideo", "pipe:1"]
        try:
            self._proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL)
        except OSError as exc:
            self.error = str(exc)
            self._ready.set()
            return
        threading.Thread(target=self._drain, daemon=True).start()

    @property
    def on_gpu(self) -> bool:
        return bool(self.info and self.info.kind != "cpu")

    def _drain(self) -> None:
        proc = self._proc
        try:
            for raw in iter(proc.stderr.readline, b""):
                line = raw.decode(errors="ignore").rstrip()
                if not self._ready.is_set():
                    m = _SIZE.search(line)
                    if m:
                        self.width, self.height = int(m.group(1)), int(m.group(2))
                        self._ready.set()
                        continue
                if line and not line.startswith(("Input #", "Output #", "  ", "Stream mapping", "Press [q]")):
                    self._lines = (self._lines + [line])[-20:]
        except (OSError, ValueError):
            pass
        finally:
            self._ready.set()

    def isOpened(self, timeout: float = OPEN_TIMEOUT) -> bool:  # noqa: N802 - cv2's name
        if self._proc is None:
            return False
        self._ready.wait(timeout)
        if not self.width:
            self.error = self.stderr() or ("no picture within %ss" % int(timeout))
            if self.on_gpu and looks_like_gpu_error(self.error):
                gpu_video.failed(self.error)
            return False
        if self.on_gpu:
            gpu_video.ok()
        return True

    def read(self):
        """(True, frame) or (False, None) at the end / on an error."""
        if self._proc is None or not self.width:
            return False, None
        n = self.width * self.height * 3
        buf = bytearray(n)
        view = memoryview(buf)
        got = 0
        try:
            while got < n:
                k = self._proc.stdout.readinto(view[got:])
                if not k:
                    break
                got += k
        except (OSError, ValueError):
            got = 0
        if got < n:
            self._finished()
            return False, None
        return True, np.frombuffer(buf, np.uint8).reshape(self.height, self.width, 3)

    def _finished(self) -> None:
        proc = self._proc
        if proc is None:
            return
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            return
        if proc.returncode not in (0, None):
            self.error = self.stderr()
            if self.on_gpu and looks_like_gpu_error(self.error):
                gpu_video.failed(self.error)

    def stderr(self) -> str:
        return " | ".join(self._lines)[-400:]

    def release(self) -> None:
        proc, self._proc = self._proc, None
        if proc is None:
            return
        if proc.poll() is None:
            proc.kill()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        for stream in (proc.stdout, proc.stderr):
            try:
                stream.close()
            except (OSError, ValueError, AttributeError):
                pass
