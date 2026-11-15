"""Which visits a given person appears in - and what kind of activity a visit was.

One rule, shared by the Visits API filter (used by the Android app) and
mirrored in the web page (frontend/src/utils/visitPeople.ts), so a person's
visit count and the filtered list always agree.

A visit names someone when they were recognised live (`identities`) or when
the after-visit review found them (`people`, kind "known" or "probable" - a
probable match is kept rather than hidden: for a security filter, missing a
real visit is worse than showing a "possibly" one, which the UI labels).
"""

from collections.abc import Iterable

# Filter value for "a face nobody recognised".
STRANGERS = "__strangers__"

_NAMED_KINDS = ("known", "probable")


def visit_names(identities: list | None, people: list | None) -> set[str]:
    names = {n for n in (identities or []) if n}
    for p in people or []:
        if isinstance(p, dict) and p.get("kind") in _NAMED_KINDS and p.get("name"):
            names.add(p["name"])
    return names


def has_stranger(strangers: int | None, people: list | None, event_payloads: Iterable[dict | None] = ()) -> bool:
    if strangers:
        return True
    if any(isinstance(p, dict) and p.get("kind") == "stranger" for p in people or []):
        return True
    return any(isinstance(pl, dict) and pl.get("unknown_faces") for pl in event_payloads)


def matches(person: str, identities: list | None, people: list | None, strangers: int | None,
            event_payloads: Iterable[dict | None] = ()) -> bool:
    if person == STRANGERS:
        return has_stranger(strangers, people, event_payloads)
    return person in visit_names(identities, people)


# --- What was seen ---------------------------------------------------------
# A visit's kinds, from what the AI confirmed (`counts`, by detector label) and
# who was recognised. "motion" is a visit where nothing was recognised -
# shadows, lights switching, headlights, rain, swaying plants.
KINDS = ("people", "vehicles", "animals", "packages", "other", "motion")
# Filter values besides a kind: "real" = anything but motion-only.
REAL = "real"

_KIND_OF_GROUP = {"person": "people", "vehicle": "vehicles", "two_wheeler": "vehicles",
                  "animal": "animals", "package": "packages"}


def visit_kinds(counts: dict | None, identities: list | None, people: list | None) -> set[str]:
    from app.services.motion_event import group_of

    kinds = {_KIND_OF_GROUP.get(group_of(label), "other") for label in (counts or {})}
    if identities or any(isinstance(p, dict) for p in people or []):
        kinds.add("people")
    return kinds or {"motion"}


def matches_kind(what: str, counts: dict | None, identities: list | None, people: list | None) -> bool:
    kinds = visit_kinds(counts, identities, people)
    if what == REAL:
        return kinds != {"motion"}
    return what in kinds


def matches_cameras(camera_ids: list | None, wanted: set[int]) -> bool:
    """The visit was seen on any of the wanted cameras (one camera, or a group's)."""
    return any(c in wanted for c in camera_ids or [])
