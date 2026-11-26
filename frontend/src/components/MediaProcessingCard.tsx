import { useEffect, useState } from "react";
import { getMediaProcessing, updateSystemSettings, type MediaProcessing } from "../api/system";

/** Settings -> System -> Media processing: what decodes the video (the GPU,
 *  by design) and where the AI models run, and the "GPU only" choice. */
export function MediaProcessingCard() {
  const [data, setData] = useState<MediaProcessing | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = () =>
    getMediaProcessing()
      .then((d) => {
        setData(d);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));

  useEffect(() => {
    load();
  }, []);

  const setGpuOnly = async (on: boolean) => {
    setSaving(true);
    try {
      await updateSystemSettings({ media_gpu_only: on });
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const v = data?.video;
  const onGpu = v && v.kind !== "cpu";
  const aiGpu = data?.ai && data.ai.active !== "cpu";
  const aiDevice = data?.ai?.devices?.find((d) => d.id === data.ai?.active);

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>Media processing</h2>
      <p style={{ fontSize: 13, color: "var(--text-dim)", marginTop: 0 }}>
        Recording, live view, playback and clip downloads never convert the video: they keep the camera's own H.264 or
        H.265. Every picture the NVR looks at (motion, AI checks, the visit review, thumbnails) is decoded on the GPU.
      </p>
      {error && <div className="error-text">{error}</div>}
      {!data && !error && <p className="muted">Checking…</p>}
      {v && (
        <div style={{ display: "grid", gap: 10, fontSize: 13 }}>
          <div>
            <strong>Video decoding: </strong>
            {v.state === "paused" ? (
              <span style={{ color: "var(--danger, #d33)" }}>
                paused — the GPU stopped working ({v.broken_reason || v.reason}). Recording carries on; motion and AI
                checks resume when it's back (it's tried every minute).
              </span>
            ) : onGpu ? (
              <span>
                GPU — {v.name}
                {v.device ? ` (${v.device})` : ""}, decodes {v.codecs.map((c) => (c === "hevc" ? "H.265" : "H.264")).join(" and ")}
                {v.state === "cpu" ? " — not working right now, the CPU stands in" : ""}
              </span>
            ) : (
              <span>
                CPU — <span className="muted">{v.reason}</span>
              </span>
            )}
          </div>
          <div>
            <strong>AI models: </strong>
            {data.ai ? (
              <span>
                {aiGpu ? `GPU — ${aiDevice?.name ?? data.ai.active}` : "CPU"}
                {data.ai.note ? <span className="muted"> ({data.ai.note})</span> : null}
                <span className="muted"> · choose in Settings → AI → AI engine</span>
              </span>
            ) : (
              <span className="muted">the AI service isn't answering{data.ai_error ? ` (${data.ai_error})` : ""}</span>
            )}
          </div>
          {onGpu && (
            <label style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
              <input
                type="checkbox"
                checked={v.gpu_only}
                disabled={saving}
                onChange={(e) => setGpuOnly(e.target.checked)}
              />
              <span>
                <strong>GPU only</strong>
                <br />
                <span className="muted">
                  Never decode on the CPU instead. If the GPU stops working, motion and AI checks pause (recording carries
                  on) and you get an alert. Off: the CPU stands in until the GPU is back.
                </span>
              </span>
            </label>
          )}
          {v.tried.length > 0 && !onGpu && (
            <details>
              <summary className="muted">What was tried</summary>
              <ul style={{ margin: "6px 0 0", paddingLeft: 18 }}>
                {v.tried.map((t) => (
                  <li key={t.device}>
                    {t.name} ({t.device}): {t.ok ? "works" : t.error || "failed"}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
    </div>
  );
}
