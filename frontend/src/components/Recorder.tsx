import { useEffect, useMemo, useRef, useState } from "react";
import { Icon } from "./ui";

/** Records a voice note with the browser's MediaRecorder. Falls back to the phone's own recorder when the
 *  page is not in a secure context (browsers only allow the microphone over HTTPS or on localhost). */
export function Recorder({ value, onChange }: { value: Blob | null; onChange: (audio: Blob | null) => void }) {
  const [state, setState] = useState<"idle" | "recording">("idle");
  const [seconds, setSeconds] = useState(0);
  const [level, setLevel] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const stream = useRef<MediaStream | null>(null);
  const audioCtx = useRef<AudioContext | null>(null);
  const raf = useRef(0);
  const timer = useRef(0);

  const playbackUrl = useMemo(() => (value ? URL.createObjectURL(value) : null), [value]);
  useEffect(() => () => void (playbackUrl && URL.revokeObjectURL(playbackUrl)), [playbackUrl]);

  const supported = typeof window !== "undefined" && window.isSecureContext && !!navigator.mediaDevices?.getUserMedia && "MediaRecorder" in window;

  const cleanup = () => {
    cancelAnimationFrame(raf.current);
    window.clearInterval(timer.current);
    stream.current?.getTracks().forEach((t) => t.stop());
    stream.current = null;
    audioCtx.current?.close().catch(() => undefined);
    audioCtx.current = null;
  };

  useEffect(() => cleanup, []);

  const start = async () => {
    setError(null);
    try {
      const media = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.current = media;
      const mime = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg"].find((m) => MediaRecorder.isTypeSupported(m));
      const rec = new MediaRecorder(media, mime ? { mimeType: mime } : undefined);
      const chunks: BlobPart[] = [];
      rec.ondataavailable = (e) => e.data.size > 0 && chunks.push(e.data);
      rec.onstop = () => {
        onChange(new Blob(chunks, { type: rec.mimeType || "audio/webm" }));
        cleanup();
        setState("idle");
      };
      recorder.current = rec;

      const ctx = new AudioContext();
      audioCtx.current = ctx;
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 256;
      ctx.createMediaStreamSource(media).connect(analyser);
      const data = new Uint8Array(analyser.frequencyBinCount);
      const tick = () => {
        analyser.getByteTimeDomainData(data);
        let peak = 0;
        for (const v of data) peak = Math.max(peak, Math.abs(v - 128));
        setLevel(Math.min(1, peak / 60));
        raf.current = requestAnimationFrame(tick);
      };
      tick();

      setSeconds(0);
      timer.current = window.setInterval(() => setSeconds((s) => s + 1), 1000);
      onChange(null);
      rec.start();
      setState("recording");
    } catch {
      cleanup();
      setError("The microphone could not be opened. Allow microphone access, or type the note instead.");
    }
  };

  const stop = () => recorder.current?.state === "recording" && recorder.current.stop();

  if (!supported) {
    return (
      <div className="recorder">
        <label className="btn btn-secondary btn-block">
          <Icon name="mic" /> {value ? "Record again" : "Record a voice note"}
          <input type="file" accept="audio/*" capture hidden onChange={(e) => onChange(e.target.files?.[0] ?? null)} />
        </label>
        {value && <p className="note note-ok">Voice note attached.</p>}
        <p className="hint">Live recording needs HTTPS. Open the HTTPS address shown in the README to record inside the app.</p>
      </div>
    );
  }

  return (
    <div className="recorder">
      {state === "recording" ? (
        <button type="button" className="btn btn-danger btn-block" onClick={stop}>
          <Icon name="stop" /> Stop · {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, "0")}
        </button>
      ) : (
        <button type="button" className="btn btn-secondary btn-block" onClick={start}>
          <Icon name="mic" /> {value ? "Record again" : "Record a voice note"}
        </button>
      )}
      {state === "recording" && (
        <div className="meter" aria-hidden="true">
          {Array.from({ length: 28 }, (_, i) => {
            const wave = 0.35 + 0.65 * Math.abs(Math.sin(i * 0.9 + seconds * 2 + level * 6));
            return <span key={i} style={{ height: `${6 + 34 * level * wave}px` }} />;
          })}
        </div>
      )}
      {playbackUrl && state === "idle" && (
        <div className="playback">
          <audio controls src={playbackUrl} />
        </div>
      )}
      {error && <p className="note note-error">{error}</p>}
    </div>
  );
}
