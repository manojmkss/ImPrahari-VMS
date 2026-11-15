import type { IncidentWithEvents } from "../api/security";

/** Filter value for "a face nobody recognised". Same value the API's
 *  `?person=` filter takes (backend/app/services/visit_filter.py). */
export const STRANGERS = "__strangers__";

/**
 * Names a visit is attributed to: recognised live (`identities`) or found by
 * the after-visit review (`people`, kind "known" or "probable"). A probable
 * match is included rather than hidden - for a security filter, missing a
 * real visit is worse than showing a "possibly" one, which the card labels.
 * Mirrors visit_names() on the backend so counts and the filter agree.
 */
export function visitNames(v: IncidentWithEvents): Set<string> {
  const names = new Set((v.identities ?? []).filter(Boolean));
  for (const p of v.people ?? []) {
    if ((p.kind === "known" || p.kind === "probable") && p.name) names.add(p.name);
  }
  return names;
}

/** A face nobody recognised: the review's stranger count/people, or an
 *  event flagged with unknown faces. */
export function visitHasStranger(v: IncidentWithEvents): boolean {
  if (v.strangers > 0) return true;
  if ((v.people ?? []).some((p) => p.kind === "stranger")) return true;
  return v.events.some((e) => (e.payload as { unknown_faces?: boolean } | null)?.unknown_faces === true);
}

export function visitMatchesPerson(v: IncidentWithEvents, person: string): boolean {
  return person === STRANGERS ? visitHasStranger(v) : visitNames(v).has(person);
}

// --- What was seen -------------------------------------------------------
// Mirrors visit_kinds() in backend/app/services/visit_filter.py (and the
// detector label groups in motion_event.LABEL_GROUPS).

export type VisitKind = "people" | "vehicles" | "animals" | "packages" | "other" | "motion";

const KIND_OF_LABEL: Record<string, VisitKind> = {
  person: "people",
  car: "vehicles", truck: "vehicles", bus: "vehicles", motorcycle: "vehicles", bicycle: "vehicles",
  dog: "animals", cat: "animals", bird: "animals", horse: "animals", cow: "animals", sheep: "animals",
  backpack: "packages", handbag: "packages", suitcase: "packages",
};

/** What a visit was: from what the AI confirmed and who was recognised.
 *  "motion" = nothing recognised (lights, shadows, headlights, plants). */
export function visitKinds(v: IncidentWithEvents): Set<VisitKind> {
  const kinds = new Set<VisitKind>(Object.keys(v.counts ?? {}).map((l) => KIND_OF_LABEL[l] ?? "other"));
  if ((v.identities?.length ?? 0) > 0 || (v.people?.length ?? 0) > 0) kinds.add("people");
  if (!kinds.size) kinds.add("motion");
  return kinds;
}

/** "real" = anything but motion only; "all" = everything. */
export type WhatFilter = VisitKind | "real" | "all";

export function visitMatchesWhat(v: IncidentWithEvents, what: WhatFilter): boolean {
  if (what === "all") return true;
  const kinds = visitKinds(v);
  if (what === "real") return !kinds.has("motion");
  return kinds.has(what);
}

/** The kind a detector label falls under (for links from the day summary). */
export const kindOfLabel = (label: string): VisitKind => KIND_OF_LABEL[label] ?? "other";
