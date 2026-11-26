"""Leftovers of the H.265 -> H.264 playback conversion of older versions.

ImPrahari no longer converts video: recordings, live view, playback and clip
downloads are the camera's own H.264/H.265, exactly as recorded. Older
versions kept converted playback copies in TRANSCODE_DIR; they are removed
at startup.
"""

import os
import shutil

TRANSCODE_DIR = "/storage/transcode-cache"


def cleanup_on_startup() -> None:
    shutil.rmtree(TRANSCODE_DIR, ignore_errors=True)
