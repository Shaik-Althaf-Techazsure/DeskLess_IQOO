import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import { Recorder } from "../components/Recorder";
import { ErrorNote, Icon, Spinner, TopBar } from "../components/ui";

export default function Capture() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const [photo, setPhoto] = useState<File | null>(null);
  const [audio, setAudio] = useState<Blob | null>(null);
  const [note, setNote] = useState("");
  const [typing, setTyping] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const preview = useMemo(() => (photo ? URL.createObjectURL(photo) : null), [photo]);
  useEffect(() => () => void (preview && URL.revokeObjectURL(preview)), [preview]);

  const ready = !!photo || !!audio || note.trim().length > 0;

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const item = await api.addItem(id, { photo, audio, note });
      navigate(`/w/${id}/item/${item.id}`, { replace: true, state: { fresh: true, warnings: item.warnings } });
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="page has-bottombar">
      <TopBar title="New issue" subtitle="A photo and a sentence are enough" back={`/w/${id}`} />
      <ErrorNote message={error} />

      <section className="card step">
        <h2>
          <span className="stepnum">1</span> Photograph the issue
        </h2>
        {preview && <img src={preview} alt="The photo you took" className="preview" />}
        <label className={`btn ${photo ? "btn-ghost" : "btn-secondary"} btn-block`}>
          <Icon name="camera" /> {photo ? "Retake photo" : "Take a photo"}
          <input type="file" accept="image/*" capture="environment" hidden onChange={(e) => setPhoto(e.target.files?.[0] ?? null)} />
        </label>
      </section>

      <section className="card step">
        <h2>
          <span className="stepnum">2</span> Say what is wrong
        </h2>
        <p className="hint">Speak the way you normally do on site, in Hindi, English or a mix. Mention who should fix it and by when.</p>
        {typing ? (
          <textarea
            rows={4}
            autoFocus
            placeholder="Example: Yahan panel ki wiring loose hai. Electrician ko kal tak bulana hai."
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        ) : (
          <Recorder value={audio} onChange={setAudio} />
        )}
        <button type="button" className="linkbtn" onClick={() => setTyping(!typing)}>
          {typing ? "Record a voice note instead" : "Type the note instead"}
        </button>
      </section>

      <nav className="bottombar">
        {busy ? (
          <Spinner label="Transcribing and structuring on this machine…" />
        ) : (
          <button className="btn btn-primary btn-block btn-lg" onClick={submit} disabled={!ready}>
            <Icon name="check" /> Create item
          </button>
        )}
      </nav>
    </div>
  );
}
