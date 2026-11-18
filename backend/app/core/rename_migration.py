"""One-time move of the database file from the project's previous name.

ImPrahari VMS was published under another name before, and its SQLite
database file was named after it. Run before the engine opens the database:
if the configured file doesn't exist yet, but the same folder holds exactly
one other SQLite file that is this app's database (it has the cameras, users
and recordings tables), that file - with its -wal/-shm companions - is renamed
into place. Same volume, so it's an atomic rename: nothing is copied. The
previous name isn't needed (or written) anywhere: the file is recognised by
what's in it.
"""

import glob
import logging
import os
import sqlite3

from sqlalchemy.engine import make_url

logger = logging.getLogger(__name__)

# Tables only this app's database has.
_OWN_TABLES = {"cameras", "users", "recordings"}


def _is_our_database(path: str) -> bool:
    try:
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=2)
        try:
            names = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        finally:
            con.close()
    except sqlite3.Error:
        return False
    return _OWN_TABLES <= names


def migrate_database_file(database_url: str) -> bool:
    """Rename the previous name's database to the configured SQLite file. True if moved."""
    try:
        url = make_url(database_url)
    except Exception:
        return False
    if not url.drivername.startswith("sqlite") or not url.database or url.database == ":memory:":
        return False
    path = os.path.abspath(url.database)
    if os.path.exists(path):
        return False
    folder = os.path.dirname(path)
    candidates = [p for p in glob.glob(os.path.join(folder, "*.db")) if os.path.isfile(p) and _is_our_database(p)]
    if len(candidates) != 1:
        if len(candidates) > 1:
            logger.warning("Not renaming a database: %d candidates in %s", len(candidates), folder)
        return False
    previous = candidates[0]
    # Companions first, the main file last: a crash part-way leaves the old
    # main file in place, so the next start simply finishes the move.
    for suffix in ("-wal", "-shm", ""):
        if os.path.exists(previous + suffix):
            os.replace(previous + suffix, path + suffix)
    logger.warning("Moved the database %s to %s (the project's new name)", previous, path)
    return True
