"""Visits filtered by person: the shared matcher and the /incidents route."""

import asyncio
from datetime import datetime, timedelta

from app.services.visit_filter import STRANGERS, has_stranger, matches, visit_names


def test_names_come_from_live_recognition_and_the_review():
    people = [
        {"kind": "known", "name": "Asha"},
        {"kind": "probable", "name": "Ravi"},
        {"kind": "stranger", "name": None},
        {"kind": "unseen", "name": None},
    ]
    assert visit_names(["Manoj"], people) == {"Manoj", "Asha", "Ravi"}
    assert visit_names(None, None) == set()


def test_unnamed_or_unseen_people_never_match_a_name():
    people = [{"kind": "unseen", "name": "Asha"}, {"kind": "stranger", "name": "Asha"}]
    assert not matches("Asha", None, people, 0)


def test_strangers_from_the_count_the_review_or_an_event():
    assert has_stranger(1, None)
    assert has_stranger(0, [{"kind": "stranger", "name": None}])
    assert has_stranger(0, None, [None, {"unknown_faces": True}])
    assert not has_stranger(0, [{"kind": "known", "name": "Asha"}], [{"unknown_faces": False}, None])
    assert matches(STRANGERS, None, None, 2)


def test_names_are_exact():
    assert not matches("manoj", ["Manoj"], None, 0)
    assert not matches("Mano", ["Manoj"], None, 0)


def _db(tmp_path):
    import app.models  # noqa: F401
    from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

    from app.db.session import Base

    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path}/t.db")

    async def setup():
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

    asyncio.run(setup())
    return engine, async_sessionmaker(engine, expire_on_commit=False)


def test_route_filters_by_person_then_pages(tmp_path):
    from app.api.routes.security import list_incidents
    from app.models.event import Event
    from app.models.incident import Incident

    engine, maker = _db(tmp_path)
    t0 = datetime(2026, 11, 15, 8, 0, 0)

    async def go():
        async with maker() as db:
            db.add_all([
                Incident(id=1, status="closed", started_at=t0, identities=["Manoj"]),
                Incident(id=2, status="closed", started_at=t0 + timedelta(minutes=5),
                         people=[{"kind": "known", "name": "Manoj"}]),
                Incident(id=3, status="closed", started_at=t0 + timedelta(minutes=10),
                         people=[{"kind": "known", "name": "Asha"}]),
                Incident(id=4, status="closed", started_at=t0 + timedelta(minutes=15), strangers=1),
                # Stranger only known from one of its events.
                Incident(id=5, status="closed", started_at=t0 + timedelta(minutes=20)),
                Incident(id=6, status="closed", started_at=t0 + timedelta(minutes=25),
                         identities=["Manoj"], people=[{"kind": "probable", "name": "Manoj"}]),
            ])
            db.add(Event(camera_id=1, type="motion", message="m", incident_id=5, payload={"unknown_faces": True}))
            await db.commit()

        async def ids(person, limit=50, offset=0, include_events=False):
            async with maker() as db:
                rows = await list_incidents(limit=limit, offset=offset, start=None, end=None,
                                            include_events=include_events, person=person, db=db, _=None)
            return [r.id for r in rows]

        everyone = await ids(None)
        manoj = await ids("Manoj")
        first_page = await ids("Manoj", limit=2)
        second_page = await ids("Manoj", limit=2, offset=2)
        strangers = await ids(STRANGERS)
        nobody = await ids("Nobody")
        with_events = await ids("Manoj", limit=1, include_events=True)
        await engine.dispose()
        return everyone, manoj, first_page, second_page, strangers, nobody, with_events

    everyone, manoj, first_page, second_page, strangers, nobody, with_events = asyncio.run(go())
    assert everyone == [6, 5, 4, 3, 2, 1]
    assert manoj == [6, 2, 1]  # newest first; counted once even when named twice
    assert first_page == [6, 2] and second_page == [1]  # paging applies after the filter
    assert strangers == [5, 4]
    assert nobody == []
    assert with_events == [6]


def test_what_was_seen():
    from app.services.visit_filter import matches_kind, visit_kinds

    assert visit_kinds({"person": 2}, None, None) == {"people"}
    assert visit_kinds({"car": 1, "truck": 1, "motorcycle": 1}, None, None) == {"vehicles"}
    assert visit_kinds({"dog": 1, "backpack": 1, "umbrella": 1}, None, None) == {"animals", "packages", "other"}
    # Recognised by face (or found by the review) counts as people even when
    # the counts missed the person.
    assert visit_kinds(None, ["Manoj"], None) == {"people"}
    assert visit_kinds({}, None, [{"kind": "unseen", "name": None}]) == {"people"}
    # Nothing recognised: lights, shadows, plants.
    assert visit_kinds({}, [], []) == {"motion"}
    assert matches_kind("motion", None, None, None)
    assert not matches_kind("real", None, None, None)
    assert matches_kind("real", {"cat": 1}, None, None)
    assert not matches_kind("people", {"car": 1}, None, None)
    assert matches_kind("vehicles", {"person": 1, "car": 1}, None, None)


def test_route_filters_by_what_camera_and_group(tmp_path):
    from app.api.routes.security import list_incidents
    from app.models.camera import Camera
    from app.models.incident import Incident

    engine, maker = _db(tmp_path)
    t0 = datetime(2026, 11, 15, 8, 0, 0)

    async def go():
        async with maker() as db:
            db.add_all([
                Camera(id=1, name="Gate", rtsp_main_url="rtsp://x/1", area="Front"),
                Camera(id=2, name="Porch", rtsp_main_url="rtsp://x/2", area="front "),
                Camera(id=3, name="Yard", rtsp_main_url="rtsp://x/3", area="Back"),
            ])
            db.add_all([
                Incident(id=1, status="closed", started_at=t0, camera_ids=[1], counts={"person": 1}),
                Incident(id=2, status="closed", started_at=t0 + timedelta(minutes=5), camera_ids=[3], counts={"car": 1}),
                Incident(id=3, status="closed", started_at=t0 + timedelta(minutes=10), camera_ids=[2]),  # lights
                Incident(id=4, status="closed", started_at=t0 + timedelta(minutes=15), camera_ids=[2, 3],
                         counts={"person": 1, "car": 1}, identities=["Manoj"]),
            ])
            await db.commit()

        async def ids(**kw):
            async with maker() as db:
                rows = await list_incidents(limit=50, offset=0, start=None, end=None, include_events=False,
                                            db=db, _=None, **kw)
            return [r.id for r in rows]

        out = {
            "people": await ids(what="people"),
            "vehicles": await ids(what="vehicles"),
            "motion": await ids(what="motion"),
            "real": await ids(what="real"),
            "cam3": await ids(camera_id=3),
            "front": await ids(group="Front"),
            "front_people": await ids(group="FRONT", what="people"),
            "cam3_in_front": await ids(camera_id=3, group="Front"),
            "manoj_vehicles_back": await ids(person="Manoj", what="vehicles", group="Back"),
            "no_group": await ids(group="Garage"),
        }
        await engine.dispose()
        return out

    out = asyncio.run(go())
    assert out["people"] == [4, 1]
    assert out["vehicles"] == [4, 2]
    assert out["motion"] == [3]
    assert out["real"] == [4, 2, 1]
    assert out["cam3"] == [4, 2]
    assert out["front"] == [4, 3, 1]  # group names match ignoring case and spaces
    assert out["front_people"] == [4, 1]
    assert out["cam3_in_front"] == []  # camera 3 isn't in that group
    assert out["manoj_vehicles_back"] == [4]
    assert out["no_group"] == []
