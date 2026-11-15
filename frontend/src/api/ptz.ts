import { apiFetch } from "./client";

/** What a camera's PTZ can do (GET /cameras/{id}/ptz). Admin only. */
export interface PtzInfo {
  supported: boolean;
  reason: string | null;
  pan_tilt: boolean;
  zoom: boolean;
  presets: boolean;
  max_presets: number;
  home: boolean;
  presets_list: { token: string; name: string }[];
  presets_error?: string;
}

const base = (id: number) => `/cameras/${id}/ptz`;

export const getPtz = (id: number) => apiFetch<PtzInfo>(base(id));
/** Speeds -1..1 (pan right +, tilt up +, zoom in +). Lasts under a second:
 *  send again while the button is held. */
export const ptzMove = (id: number, pan: number, tilt: number, zoom: number) =>
  apiFetch<void>(`${base(id)}/move`, { method: "POST", body: JSON.stringify({ pan, tilt, zoom }) });
export const ptzStop = (id: number) => apiFetch<void>(`${base(id)}/stop`, { method: "POST" });
export const ptzSavePreset = (id: number, name: string) =>
  apiFetch<{ token: string; name: string }>(`${base(id)}/presets`, { method: "POST", body: JSON.stringify({ name }) });
export const ptzGotoPreset = (id: number, token: string) =>
  apiFetch<void>(`${base(id)}/presets/${encodeURIComponent(token)}/goto`, { method: "POST" });
export const ptzRemovePreset = (id: number, token: string) =>
  apiFetch<void>(`${base(id)}/presets/${encodeURIComponent(token)}`, { method: "DELETE" });
export const ptzGotoHome = (id: number) => apiFetch<void>(`${base(id)}/home/goto`, { method: "POST" });
export const ptzSetHome = (id: number) => apiFetch<void>(`${base(id)}/home/set`, { method: "POST" });
