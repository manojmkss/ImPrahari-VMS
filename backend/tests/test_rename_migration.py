"""Moving an install made under the project's previous name to ImPrahari VMS.

The database file is renamed by the backend on start; the update/install
scripts find the previous name from the install itself, rename the .env
settings, copy the Docker volumes and swap the host guardian unit. The
scripts run against a stand-in `docker` that keeps volumes as folders and
runs the scripts' own `sh -c` copy for real. "oldcam" plays the previous name:
nothing here depends on what it really was.
"""

import os
import shutil
import sqlite3
import subprocess
import textwrap
import time

import pytest

from app.core.rename_migration import migrate_database_file

LIB = os.path.join(os.path.dirname(__file__), "..", "..", "scripts", "lib-env.sh")
OLD = "oldcam"
MARKER = ".moved-from-previous-name"


# ── database file (backend) ──────────────────────────────────────────────────


def _db(path, rows, tables=("cameras", "users", "recordings")):
    con = sqlite3.connect(path)
    con.execute("PRAGMA journal_mode=WAL")
    for t in tables:
        con.execute(f"CREATE TABLE IF NOT EXISTS {t} (v TEXT)")
    con.executemany(f"INSERT INTO {tables[0]} VALUES (?)", [(r,) for r in rows])
    con.commit()
    return con


def _crash_copy(tmp_path, name):
    """Open the database, then put back what a power cut would leave: the WAL un-checkpointed."""
    con = _db(tmp_path / name, ["camera 1", "camera 2"])
    for s in ("", "-wal", "-shm"):
        if (tmp_path / f"{name}{s}").exists():
            shutil.copy(tmp_path / f"{name}{s}", tmp_path / f"crash{s}")
    con.close()
    for s in ("", "-wal", "-shm"):
        if (tmp_path / f"crash{s}").exists():
            shutil.move(tmp_path / f"crash{s}", tmp_path / f"{name}{s}")


def test_previous_database_is_found_by_its_tables_and_keeps_every_row(tmp_path):
    _crash_copy(tmp_path, f"{OLD}.db")
    assert (tmp_path / f"{OLD}.db-wal").exists()
    url = f"sqlite+aiosqlite:///{tmp_path}/imprahari.db"
    assert migrate_database_file(url) is True
    assert not any(p.name.startswith(f"{OLD}.db") for p in tmp_path.iterdir())
    rows = sqlite3.connect(tmp_path / "imprahari.db").execute("SELECT v FROM cameras ORDER BY v").fetchall()
    assert rows == [("camera 1",), ("camera 2",)]
    assert migrate_database_file(url) is False  # second start: nothing to do


def test_an_existing_new_database_is_never_replaced(tmp_path):
    _db(tmp_path / f"{OLD}.db", ["old"]).close()
    _db(tmp_path / "imprahari.db", ["new"]).close()
    assert migrate_database_file(f"sqlite+aiosqlite:///{tmp_path}/imprahari.db") is False
    assert sqlite3.connect(tmp_path / "imprahari.db").execute("SELECT v FROM cameras").fetchall() == [("new",)]


def test_other_sqlite_files_and_ambiguous_folders_are_left_alone(tmp_path):
    _db(tmp_path / "something-else.db", ["x"], tables=("notes",)).close()
    (tmp_path / "broken.db").write_text("not sqlite")
    url = f"sqlite+aiosqlite:///{tmp_path}/imprahari.db"
    assert migrate_database_file(url) is False  # not this app's database
    _db(tmp_path / "a.db", ["a"]).close()
    _db(tmp_path / "b.db", ["b"]).close()
    assert migrate_database_file(url) is False  # two candidates: don't guess
    assert not (tmp_path / "imprahari.db").exists()


@pytest.mark.parametrize("url", ["postgresql+asyncpg://u:p@db/x", "sqlite+aiosqlite:///:memory:", "not a url"])
def test_other_databases_are_left_alone(url):
    assert migrate_database_file(url) is False


# ── scripts ──────────────────────────────────────────────────────────────────

needs_bash = pytest.mark.skipif(shutil.which("bash") is None or shutil.which("python3") is None,
                                reason="needs bash and python3")

# A stand-in `docker`: volumes are folders under $STATE/v; containers using a
# volume are listed in $STATE/using/<volume>; $STATE/backend holds the id and
# name of this compose project's backend container (if it still exists); and
# `run` executes the command locally with each mount path pointing at its
# volume folder.
FAKE_DOCKER = textwrap.dedent('''\
    #!/usr/bin/env python3
    import datetime, os, re, shutil, subprocess, sys, time
    st = os.environ["STATE"]
    vols = os.path.join(st, "v")
    os.makedirs(vols, exist_ok=True)
    a = sys.argv[1:]
    with open(os.path.join(st, "log"), "a") as f:
        f.write(" ".join(a) + "\\n")
    def vol(n): return os.path.join(vols, n)
    backend = os.path.join(st, "backend")
    if a[:2] == ["compose", "ps"]:
        if os.path.exists(backend): print(open(backend).read().split()[0])
        sys.exit(0)
    if a[0] == "inspect":
        if os.path.exists(backend) and a[-1] == open(backend).read().split()[0]:
            print("/" + open(backend).read().split()[1]); sys.exit(0)
        sys.exit(1)
    if a[:2] == ["volume", "ls"]:
        for n in sorted(os.listdir(vols)):
            if os.path.isdir(vol(n)): print(n)
        sys.exit(0)
    if a[:2] == ["volume", "inspect"]:
        n = a[-1]
        if not os.path.isdir(vol(n)): sys.exit(1)
        if "-f" in a:
            t = float(open(vol(n) + ".created").read())
            print(datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
        sys.exit(0)
    if a[:2] == ["volume", "create"]:
        os.makedirs(vol(a[2]), exist_ok=True)
        open(vol(a[2]) + ".created", "w").write(str(time.time()))
        print(a[2]); sys.exit(0)
    if a[:2] == ["volume", "rm"]:
        shutil.rmtree(vol(a[2])); os.remove(vol(a[2]) + ".created"); sys.exit(0)
    if a[:2] == ["ps", "-q"]:
        n = a[a.index("--filter") + 1].split("=", 1)[1]
        p = os.path.join(st, "using", n)
        if os.path.exists(p): print(open(p).read().strip())
        sys.exit(0)
    if a[0] in ("stop", "start"):
        sys.exit(0)
    if a[0] == "run":
        mounts, env, i = {}, dict(os.environ), 1
        while i < len(a):
            if a[i] == "--rm": i += 1; continue
            if a[i] == "-v":
                name, path = a[i + 1].split(":")[:2]; mounts[path] = vol(name); i += 2; continue
            if a[i] == "-e":
                k, v = a[i + 1].split("=", 1); env[k] = v; i += 2; continue
            break
        cmd = a[i + 1:]  # after the image name
        if cmd[:2] == ["df", "-Pk"] and os.environ.get("FREE_KB"):
            print("Filesystem 1K-blocks Used Available Use% Mounted"); print(f"x 1 1 {os.environ['FREE_KB']} 1% /to"); sys.exit(0)
        if cmd[:2] == ["sh", "-c"] and os.environ.get("FAIL_COPY"):
            sys.exit(1)
        def sub(s):
            # "/to" and "/to/x" but not "/tmp"; "/d/x" but not "/data".
            for k in sorted(mounts, key=len, reverse=True):
                s = re.sub(re.escape(k) + r"(?![A-Za-z0-9_])", lambda _m: mounts[k], s)
            return s
        sys.exit(subprocess.run([sub(c) for c in cmd], env=env).returncode)
    sys.exit(0)
''')


def _setup(tmp_path, backend_name=f"{OLD}-backend"):
    st = tmp_path / "state"
    (st / "using").mkdir(parents=True)
    if backend_name:
        (st / "backend").write_text(f"c0ffee {backend_name}")
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    (bin_dir / "docker").write_text(FAKE_DOCKER)
    (bin_dir / "docker").chmod(0o755)
    return st, bin_dir


def _volume(st, name, files, age_s=0):
    d = st / "v" / name
    d.mkdir(parents=True)
    for rel, text in files.items():
        (d / rel).parent.mkdir(parents=True, exist_ok=True)
        (d / rel).write_text(text)
    (st / "v" / f"{name}.created").write_text(str(time.time() - age_s))
    return d


def _bash(tmp_path, st, bin_dir, body, env_text="", **env):
    (tmp_path / ".env").write_text(env_text)
    script = f'set -euo pipefail; ENV_FILE="{tmp_path}/.env"; DOCKER=(docker); source "{LIB}"; {body}'
    return subprocess.run(["bash", "-c", script], capture_output=True, text=True,
                          env={**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", "STATE": str(st), **env})


def _found(tmp_path, st, bin_dir):
    out = _bash(tmp_path, st, bin_dir, 'find_previous_name; echo "PREV=$PREVIOUS_NAME"')
    assert out.returncode == 0, out.stderr
    return out.stdout.strip().split("PREV=")[-1]


@needs_bash
def test_the_previous_name_is_read_from_the_install_itself(tmp_path):
    st, bin_dir = _setup(tmp_path)
    assert _found(tmp_path, st, bin_dir) == OLD  # from the running backend container


@needs_bash
def test_the_previous_name_is_found_from_its_data_volume_once_the_containers_are_gone(tmp_path):
    st, bin_dir = _setup(tmp_path, backend_name="imprahari-backend")  # already recreated under the new name
    _volume(st, "photos-data", {"pics.db": "x"})  # someone else's volume: no "<name>.db" of its own
    _volume(st, f"{OLD}-data", {f"{OLD}.db": "db"})
    assert _found(tmp_path, st, bin_dir) == OLD


@needs_bash
def test_a_fresh_install_has_no_previous_name_and_nothing_moves(tmp_path):
    st, bin_dir = _setup(tmp_path, backend_name="imprahari-backend")
    _volume(st, "imprahari-data", {"imprahari.db": "db"})
    assert _found(tmp_path, st, bin_dir) == ""
    out = _bash(tmp_path, st, bin_dir, "migrate_former_env; migrate_former_volumes", "AI_CPUS=2\n")
    assert out.returncode == 0, out.stderr
    assert (tmp_path / ".env").read_text() == "AI_CPUS=2\n"
    assert "sh -c" not in (st / "log").read_text()


@needs_bash
def test_env_settings_are_renamed_and_nothing_else_is_touched(tmp_path):
    st, bin_dir = _setup(tmp_path)
    env = textwrap.dedent(f"""\
        {OLD.upper()}_VERSION=0.4.23
        {OLD.upper()}_MIN_FREE_GB=20
        PRIMARY_STORAGE_PATH=/mnt/{OLD}-disk
        DATABASE_URL=sqlite+aiosqlite:////data/{OLD}.db
        COMPOSE_FILE=docker-compose.yml:docker-compose.macvlan.yml
        """)
    out = _bash(tmp_path, st, bin_dir, "migrate_former_env; migrate_former_env", env)
    assert out.returncode == 0, out.stderr
    assert (tmp_path / ".env").read_text() == textwrap.dedent(f"""\
        IMPRAHARI_VERSION=0.4.23
        IMPRAHARI_MIN_FREE_GB=20
        PRIMARY_STORAGE_PATH=/mnt/{OLD}-disk
        DATABASE_URL=sqlite+aiosqlite:////data/imprahari.db
        COMPOSE_FILE=docker-compose.yml:docker-compose.macvlan.yml
        """)


@needs_bash
def test_data_volume_is_copied_at_rest_and_the_old_one_kept(tmp_path):
    st, bin_dir = _setup(tmp_path)
    _volume(st, f"{OLD}-data", {f"{OLD}.db": "db", "faces/1.jpg": "face", ".tailscale/state": "ts"})
    (st / "using" / f"{OLD}-data").write_text("c1\nc2")
    out = _bash(tmp_path, st, bin_dir, "migrate_former_volumes")
    assert out.returncode == 0, out.stderr
    new = st / "v" / "imprahari-data"
    assert (new / f"{OLD}.db").read_text() == "db"  # the backend renames it on start
    assert (new / "faces/1.jpg").read_text() == "face" and (new / ".tailscale/state").read_text() == "ts"
    assert (new / MARKER).read_text().strip() == OLD  # remembers where it came from
    assert (st / "v" / f"{OLD}-data" / f"{OLD}.db").exists()  # backup kept
    log = (st / "log").read_text()
    assert "stop -t 45 c1 c2" in log and log.index("stop -t 45") < log.index("sh -c")
    # Every later run - even with the old containers gone - has nothing to do.
    (st / "backend").unlink()
    (st / "log").write_text("")
    out = _bash(tmp_path, st, bin_dir, 'migrate_former_volumes; echo "PREV=$PREVIOUS_NAME"')
    assert out.returncode == 0 and out.stdout.strip().endswith(f"PREV={OLD}")
    assert "stop" not in (st / "log").read_text() and "sh -c" not in (st / "log").read_text()


@needs_bash
def test_a_new_volume_started_empty_moments_ago_gets_the_data_and_keeps_what_it_wrote(tmp_path):
    # An updater from before the rename keeps running after the pull and starts
    # the new version on an empty volume (the setup page shows). The next run
    # must still find the previous install and bring the data over.
    st, bin_dir = _setup(tmp_path, backend_name="imprahari-backend")
    _volume(st, f"{OLD}-data", {f"{OLD}.db": "real"})
    _volume(st, "imprahari-data", {"imprahari.db": "empty", ".hidden": "x"}, age_s=600)
    out = _bash(tmp_path, st, bin_dir, "migrate_former_volumes")
    assert out.returncode == 0, out.stderr
    new = st / "v" / "imprahari-data"
    assert (new / f"{OLD}.db").read_text() == "real"
    aside = [p for p in new.iterdir() if p.name.startswith(".replaced-")]
    assert len(aside) == 1 and (aside[0] / "imprahari.db").read_text() == "empty" and (aside[0] / ".hidden").exists()
    assert not (new / "imprahari.db").exists()  # so the backend renames the real one into place


@needs_bash
def test_a_new_volume_in_use_for_days_is_left_alone(tmp_path):
    st, bin_dir = _setup(tmp_path)
    _volume(st, f"{OLD}-data", {f"{OLD}.db": "old"})
    _volume(st, "imprahari-data", {"imprahari.db": "in use"}, age_s=3 * 86400)
    out = _bash(tmp_path, st, bin_dir, "migrate_former_volumes")
    assert out.returncode == 0, out.stderr
    assert sorted(p.name for p in (st / "v" / "imprahari-data").iterdir()) == ["imprahari.db"]


@needs_bash
def test_no_room_for_the_data_stops_before_touching_anything(tmp_path):
    st, bin_dir = _setup(tmp_path)
    _volume(st, f"{OLD}-data", {f"{OLD}.db": "db"})
    (st / "using" / f"{OLD}-data").write_text("c1")
    out = _bash(tmp_path, st, bin_dir, "migrate_former_volumes", FREE_KB="1000")
    assert out.returncode == 1 and "Not enough free disk" in out.stderr
    assert not (st / "v" / "imprahari-data").exists()
    assert "stop" not in (st / "log").read_text()  # still recording


@needs_bash
def test_downloads_are_moved_or_simply_fetched_again(tmp_path):
    st, bin_dir = _setup(tmp_path)
    _volume(st, f"{OLD}-describe-models", {"models/blob": "m"})
    out = _bash(tmp_path, st, bin_dir, f"migrate_former_volume {OLD}-describe-models imprahari-describe-models drop",
                FREE_KB="1000")
    assert out.returncode == 0 and "fetched again" in out.stderr
    assert not (st / "v" / "imprahari-describe-models").exists()
    out = _bash(tmp_path, st, bin_dir, f"migrate_former_volume {OLD}-describe-models imprahari-describe-models drop")
    assert out.returncode == 0, out.stderr
    assert (st / "v" / "imprahari-describe-models" / "models/blob").read_text() == "m"
    assert not (st / "v" / f"{OLD}-describe-models").exists()


@needs_bash
def test_a_failed_copy_restarts_what_it_stopped(tmp_path):
    st, bin_dir = _setup(tmp_path)
    _volume(st, f"{OLD}-data", {f"{OLD}.db": "db"})
    (st / "using" / f"{OLD}-data").write_text("c1")
    out = _bash(tmp_path, st, bin_dir, "migrate_former_volumes", FAIL_COPY="1")
    assert out.returncode == 1 and "untouched" in out.stderr
    log = (st / "log").read_text()
    assert "stop -t 45 c1" in log and log.rstrip().endswith("start c1")
    assert (st / "v" / f"{OLD}-data" / f"{OLD}.db").read_text() == "db"


@needs_bash
def test_previous_guardian_unit_is_removed(tmp_path):
    st, bin_dir = _setup(tmp_path)
    (bin_dir / "systemctl").write_text(f'#!/bin/sh\necho "$@" >> "{tmp_path}/systemctl.log"\n')
    (bin_dir / "systemctl").chmod(0o755)
    units, journald = tmp_path / "units", tmp_path / "journald.d"
    units.mkdir()
    journald.mkdir()
    (units / f"{OLD}-guardian.service").write_text("old")
    (journald / f"{OLD}.conf").write_text("old")
    body = (f'GUARDIAN_UNIT="{units}/imprahari-guardian.service"; GUARDIAN_JOURNALD="{journald}/imprahari.conf"; '
            f'_as_root() {{ "$@"; }}; remove_former_guardian; remove_former_guardian')
    out = _bash(tmp_path, st, bin_dir, body)
    assert out.returncode == 0, out.stderr
    assert list(units.iterdir()) == [] and list(journald.iterdir()) == []
    calls = (tmp_path / "systemctl.log").read_text().splitlines()
    assert calls == [f"disable --now {OLD}-guardian", "daemon-reload"]  # once: the second run has nothing to do
