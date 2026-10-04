import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { api, engineLabel } from "../api";
import { ErrorNote, Icon, Spinner, TopBar } from "../components/ui";
import type { Item, Severity } from "../types";

const SEVERITIES: Severity[] = ["High", "Medium", "Low"];

export default function ItemPage() {
  const params = useParams();
  const id = Number(params.id);
  const itemId = Number(params.itemId);
  const navigate = useNavigate();
  const fresh = (useLocation().state as { fresh?: boolean; warnings?: string[] } | null) ?? null;

  const [item, setItem] = useState<Item | null>(null);
  const [draft, setDraft] = useState({ title: "", location: "", owner: "", due: "", severity: "Medium" as Severity });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  const load = (it: Item) => {
    setItem(it);
    setDraft({ title: it.title, location: it.location ?? "", owner: it.owner ?? "", due: it.due ?? "", severity: it.severity });
  };

  useEffect(() => {
    api.item(itemId).then(load).catch((e: Error) => setError(e.message));
  }, [itemId]);

  if (!item) {
    return (
      <div className="page">
        <TopBar title="Item" back={`/w/${id}`} />
        <ErrorNote message={error} />
        {!error && <Spinner label="Loading…" />}
      </div>
    );
  }

  const dirty =
    draft.title !== item.title || draft.location !== (item.location ?? "") || draft.owner !== (item.owner ?? "") ||
    draft.due !== (item.due ?? "") || draft.severity !== item.severity;
  const review = (name: string) => item.needs_review.includes(name) && !(draft as Record<string, string>)[name];

  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const save = () =>
    run(async () => {
      load(await api.updateItem(item.id, draft));
      setSaved(true);
      window.setTimeout(() => setSaved(false), 1800);
    });
  const reprocess = () => run(async () => load(await api.reprocess(item.id)));
  const remove = () => {
    if (!window.confirm("Delete this item?")) return;
    void run(async () => {
      await api.deleteItem(item.id);
      navigate(`/w/${id}`, { replace: true });
    });
  };

  const field = (name: "title" | "location" | "owner" | "due", label: string, placeholder: string) => (
    <label className={review(name) ? "needs-review" : ""}>
      <span>
        {label}
        {review(name) && <em>Not stated. Please confirm.</em>}
      </span>
      <input value={draft[name]} placeholder={placeholder} maxLength={name === "due" ? 60 : 200} onChange={(e) => setDraft({ ...draft, [name]: e.target.value })} />
    </label>
  );

  const warnings = [...(fresh?.warnings ?? []), ...item.warnings];
  return (
    <div className="page has-bottombar">
      <TopBar
        title={`Item ${item.seq}`}
        subtitle={fresh?.fresh ? "Check the details, then save" : engineLabel(item.engine)}
        back={`/w/${id}`}
        right={
          <button className="iconbtn" onClick={remove} aria-label="Delete item" disabled={busy}>
            <Icon name="trash" />
          </button>
        }
      />
      <ErrorNote message={error} />
      {warnings.map((w) => (
        <p key={w} className="note note-warn">
          {w}
        </p>
      ))}

      {item.photo_url && <img src={item.photo_url} alt="Photo of the issue" className="preview" />}

      {(item.transcript || item.audio_url) && (
        <section className="card">
          <h2 className="label">{item.audio_url ? "Voice note" : "Note"}</h2>
          {item.transcript && <p className="quote">“{item.transcript}”</p>}
          {item.transcript_en && item.transcript_en !== item.transcript && <p className="translation">{item.transcript_en}</p>}
          {item.audio_url && <audio controls src={item.audio_url} className="audio" />}
        </section>
      )}

      <section className="card form">
        <h2 className="label label-accent">Structured item · {engineLabel(item.engine)}</h2>
        {field("title", "Issue", "What is wrong")}
        {field("location", "Location", "Where it is")}
        <div className={`fieldgroup ${review("severity") ? "needs-review" : ""}`}>
          <span>Severity</span>
          <div className="segmented" role="radiogroup" aria-label="Severity">
            {SEVERITIES.map((s) => (
              <button key={s} type="button" role="radio" aria-checked={draft.severity === s} className={draft.severity === s ? `on sev-${s.toLowerCase()}` : ""} onClick={() => setDraft({ ...draft, severity: s })}>
                {s}
              </button>
            ))}
          </div>
        </div>
        {field("owner", "Owner", "Who should fix it")}
        {field("due", "Due", "Today, Tomorrow, This week…")}
        <button type="button" className="linkbtn" onClick={reprocess} disabled={busy}>
          <Icon name="refresh" size={16} /> Structure again from the note
        </button>
      </section>

      <nav className="bottombar">
        {busy ? (
          <Spinner label="Working…" />
        ) : dirty ? (
          <button className="btn btn-primary btn-block btn-lg" onClick={save}>
            <Icon name="check" /> Save changes
          </button>
        ) : (
          <>
            <Link to={`/w/${id}/add`} className="btn btn-secondary grow btn-lg">
              <Icon name="camera" /> Next issue
            </Link>
            <Link to={`/w/${id}`} className="btn btn-primary grow btn-lg">
              {saved ? "Saved" : "Done"}
            </Link>
          </>
        )}
      </nav>
    </div>
  );
}
