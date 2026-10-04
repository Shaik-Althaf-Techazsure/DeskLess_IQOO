import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api";
import { ErrorNote, Icon, Spinner, StatusPill } from "../components/ui";
import type { Health, InspectionSummary } from "../types";

export default function Home() {
  const navigate = useNavigate();
  const [list, setList] = useState<InspectionSummary[] | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ site_name: "", area: "", inspector: "" });

  useEffect(() => {
    api.list().then(setList).catch((e: Error) => setError(e.message));
    api.health().then(setHealth).catch(() => undefined);
  }, []);

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const insp = await api.create(form);
      navigate(`/w/${insp.id}`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };

  const loadDemo = async () => {
    setBusy(true);
    setError(null);
    try {
      const insp = await api.demo();
      navigate(`/w/${insp.id}`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <header className="brand">
        <img src="/icon.svg" alt="" width={44} height={44} />
        <div>
          <h1>DeskLess</h1>
          <p>Site inspection reports, without the desk</p>
        </div>
      </header>
      <div className="statusrow">
        <StatusPill health={health} />
      </div>

      <ErrorNote message={error} />

      {showForm ? (
        <form className="card form" onSubmit={create}>
          <h2>New walk-through</h2>
          <label>
            Site
            <input required autoFocus maxLength={120} placeholder="Tower B" value={form.site_name} onChange={(e) => setForm({ ...form, site_name: e.target.value })} />
          </label>
          <label>
            Area
            <input maxLength={120} placeholder="Basement B1" value={form.area} onChange={(e) => setForm({ ...form, area: e.target.value })} />
          </label>
          <label>
            Inspector
            <input maxLength={120} placeholder="Your name" value={form.inspector} onChange={(e) => setForm({ ...form, inspector: e.target.value })} />
          </label>
          <div className="row">
            <button type="button" className="btn btn-ghost" onClick={() => setShowForm(false)} disabled={busy}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary grow" disabled={busy}>
              Start walk-through
            </button>
          </div>
        </form>
      ) : (
        <button className="btn btn-primary btn-block btn-lg" onClick={() => setShowForm(true)} disabled={busy}>
          <Icon name="plus" /> New walk-through
        </button>
      )}

      <section>
        <h2 className="section-title">Walk-throughs</h2>
        {list === null && !error && <Spinner label="Loading…" />}
        {list?.length === 0 && (
          <div className="card empty">
            <p>No walk-throughs yet. Start one above, or load a sample to see how a finished report looks.</p>
            <button className="btn btn-secondary" onClick={loadDemo} disabled={busy}>
              {busy ? "Loading sample…" : "Load the sample walk-through"}
            </button>
          </div>
        )}
        <ul className="list">
          {list?.map((w) => (
            <li key={w.id}>
              <Link to={`/w/${w.id}`} className="card card-link">
                <div className="grow">
                  <h3>{w.site_name}</h3>
                  <p className="muted">
                    {[w.area, new Date(w.created_at).toLocaleDateString()].filter(Boolean).join(" · ")}
                  </p>
                </div>
                <div className="counts">
                  <strong>{w.item_count}</strong>
                  <span>{w.item_count === 1 ? "item" : "items"}</span>
                  {w.high_count > 0 && <span className="chip sev-high">{w.high_count} high</span>}
                </div>
              </Link>
            </li>
          ))}
        </ul>
        {list && list.length > 0 && (
          <button className="btn btn-ghost btn-block" onClick={loadDemo} disabled={busy}>
            {busy ? "Loading sample…" : "Load the sample walk-through"}
          </button>
        )}
      </section>

      {health && (
        <p className="hint center">
          Speech-to-text: {health.speech.enabled ? `Whisper ${health.speech.model}, on this machine` : "off"}. Structuring:{" "}
          {health.llm.model_ready ? `${health.llm.model}, on this machine` : "built-in rules"}. Nothing is sent to the internet.
        </p>
      )}
    </div>
  );
}
