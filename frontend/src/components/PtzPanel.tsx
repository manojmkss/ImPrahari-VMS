import { useCallback, useEffect, useRef, useState } from "react";
import {
  getPtz,
  ptzGotoHome,
  ptzGotoPreset,
  ptzMove,
  ptzRemovePreset,
  ptzSavePreset,
  ptzSetHome,
  ptzStop,
  type PtzInfo,
} from "../api/ptz";

/** How often a held button re-sends its move (the NVR stops a move by itself
 *  after 0.8 s without one, so a lost connection never leaves it turning). */
const REPEAT_MS = 300;

type Dir = { pan: number; tilt: number; zoom: number };

const PAD: { label: string; title: string; dir: Dir | null }[] = [
  { label: "↖", title: "Up-left", dir: { pan: -1, tilt: 1, zoom: 0 } },
  { label: "▲", title: "Up", dir: { pan: 0, tilt: 1, zoom: 0 } },
  { label: "↗", title: "Up-right", dir: { pan: 1, tilt: 1, zoom: 0 } },
  { label: "◀", title: "Left", dir: { pan: -1, tilt: 0, zoom: 0 } },
  { label: "", title: "", dir: null },
  { label: "▶", title: "Right", dir: { pan: 1, tilt: 0, zoom: 0 } },
  { label: "↙", title: "Down-left", dir: { pan: -1, tilt: -1, zoom: 0 } },
  { label: "▼", title: "Down", dir: { pan: 0, tilt: -1, zoom: 0 } },
  { label: "↘", title: "Down-right", dir: { pan: 1, tilt: -1, zoom: 0 } },
];

/**
 * Pan / tilt / lens zoom for a camera with motors, in the full-screen tile.
 * Shows nothing for a camera without PTZ, or for a viewer account (the NVR
 * only lets admins turn cameras).
 */
export function PtzPanel({ cameraId }: { cameraId: number }) {
  const [info, setInfo] = useState<PtzInfo | null>(null);
  const [open, setOpen] = useState(true);
  const [speed, setSpeed] = useState(0.5);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const moving = useRef(false);

  const load = useCallback(() => {
    getPtz(cameraId)
      .then((i) => setInfo(i))
      .catch(() => setInfo(null)); // viewer (403) or unreachable: no panel
  }, [cameraId]);
  useEffect(load, [load]);

  const fail = (e: unknown) => setError((e as Error).message);

  const end = useCallback(() => {
    if (timer.current) clearInterval(timer.current);
    timer.current = null;
    if (moving.current) {
      moving.current = false;
      ptzStop(cameraId).catch(fail);
    }
  }, [cameraId]);
  // Leaving full screen (or the page) mid-move stops the camera.
  useEffect(() => end, [end]);

  const start = (d: Dir) => {
    end();
    setError(null);
    const send = () =>
      ptzMove(cameraId, d.pan * speed, d.tilt * speed, d.zoom * speed).catch((e) => {
        fail(e);
        if (timer.current) clearInterval(timer.current);
        timer.current = null;
      });
    moving.current = true;
    send();
    timer.current = setInterval(send, REPEAT_MS);
  };

  if (!info?.supported) return null;

  // Hold to move: press, keep holding, release (or slide off) to stop.
  const hold = (d: Dir) => ({
    onPointerDown: (e: React.PointerEvent) => {
      e.stopPropagation();
      try {
        // Keep receiving the release even if the finger slides off the button.
        (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
      } catch {
        // not a live pointer (e.g. synthetic) - pointerup/cancel still end it
      }
      start(d);
    },
    onPointerUp: end,
    onPointerCancel: end,
    onLostPointerCapture: end,
    onContextMenu: (e: React.MouseEvent) => e.preventDefault(),
  });

  const act = (p: Promise<unknown>, reload = false) => {
    setError(null);
    p.then(() => reload && load()).catch(fail);
  };

  return (
    <div className="ptz-panel" onClick={(e) => e.stopPropagation()} onDoubleClick={(e) => e.stopPropagation()}>
      <button className="ptz-toggle" onClick={() => setOpen((o) => !o)} title="Pan, tilt and zoom the camera">
        PTZ {open ? "▾" : "▸"}
      </button>
      {open && (
        <div className="ptz-body">
          {info.pan_tilt && (
            <div className="ptz-pad">
              {PAD.map((b, i) =>
                b.dir ? (
                  <button key={i} className="ptz-btn" title={`${b.title} (hold)`} {...hold(b.dir)}>
                    {b.label}
                  </button>
                ) : info.home ? (
                  <button key={i} className="ptz-btn" title="Home position" onClick={() => act(ptzGotoHome(cameraId))}>
                    ⌂
                  </button>
                ) : (
                  <span key={i} />
                ),
              )}
            </div>
          )}
          {info.zoom && (
            <div className="ptz-row">
              <button className="ptz-btn ptz-wide" title="Lens zoom out (hold)" {...hold({ pan: 0, tilt: 0, zoom: -1 })}>
                − Zoom
              </button>
              <button className="ptz-btn ptz-wide" title="Lens zoom in (hold)" {...hold({ pan: 0, tilt: 0, zoom: 1 })}>
                Zoom +
              </button>
            </div>
          )}
          <label className="ptz-speed" title="Speed">
            Speed
            <input type="range" min={0.1} max={1} step={0.1} value={speed} onChange={(e) => setSpeed(Number(e.target.value))} />
          </label>
          {info.presets && (
            <div className="ptz-presets">
              {info.presets_list.map((p) => (
                <span key={p.token} className="ptz-preset">
                  <button onClick={() => act(ptzGotoPreset(cameraId, p.token))} title={`Go to ${p.name}`}>
                    {p.name}
                  </button>
                  <button
                    className="ptz-x"
                    title={`Delete ${p.name}`}
                    onClick={() => window.confirm(`Delete the position "${p.name}"?`) && act(ptzRemovePreset(cameraId, p.token), true)}
                  >
                    ×
                  </button>
                </span>
              ))}
              <form
                className="ptz-save"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (!name.trim()) return;
                  act(ptzSavePreset(cameraId, name.trim()), true);
                  setName("");
                }}
              >
                <input value={name} placeholder="Name this view" maxLength={64} onChange={(e) => setName(e.target.value)} />
                <button type="submit" disabled={!name.trim()} title="Save where the camera points now">
                  Save
                </button>
              </form>
              {info.home && (
                <button
                  className="ptz-link"
                  onClick={() => window.confirm("Make this view the camera's home position?") && act(ptzSetHome(cameraId))}
                >
                  Set as home
                </button>
              )}
            </div>
          )}
          {(error || info.presets_error) && <div className="ptz-error">{error || info.presets_error}</div>}
        </div>
      )}
    </div>
  );
}
