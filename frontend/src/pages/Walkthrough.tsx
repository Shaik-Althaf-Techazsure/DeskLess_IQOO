import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import { ErrorNote, Icon, SeverityChip, Spinner, TopBar } from "../components/ui";
import type { Inspection } from "../types";

export default function Walkthrough() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const [insp, setInsp] = useState<Inspection | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get(id).then(setInsp).catch((e: Error) => setError(e.message));
  }, [id]);

  const remove = async () => {
    if (!window.confirm("Delete this walk-through and all of its photos and notes?")) return;
    try {
      await api.remove(id);
      navigate("/");
    } catch (e) {
      setError((e as Error).message);
    }
  };

  if (!insp) {
    return (
      <div className="page">
        <TopBar title="Walk-through" back="/" />
        <ErrorNote message={error} />
        {!error && <Spinner label="Loading…" />}
      </div>
    );
  }

  const review = insp.items.filter((i) => i.needs_review.length > 0).length;
  return (
    <div className="page has-bottombar">
      <TopBar
        title={insp.site_name}
        subtitle={[insp.area, `${insp.item_count} ${insp.item_count === 1 ? "item" : "items"}`].filter(Boolean).join(" · ")}
        back="/"
        right={
          <button className="iconbtn" onClick={remove} aria-label="Delete walk-through">
            <Icon name="trash" />
          </button>
        }
      />
      <ErrorNote message={error} />

      {insp.items.length === 0 && (
        <div className="card empty">
          <p>Walk the site. For each issue, take a photo and say what is wrong.</p>
        </div>
      )}
      {review > 0 && (
        <p className="note note-warn">
          {review} {review === 1 ? "item has" : "items have"} fields that were not stated. Open them to confirm before sending the report.
        </p>
      )}

      <ul className="list">
        {insp.items.map((item) => (
          <li key={item.id}>
            <Link to={`/w/${id}/item/${item.id}`} className="card card-link item-card">
              {item.thumb_url ? <img src={item.thumb_url} alt="" className="thumb" /> : <div className="thumb thumb-empty">No photo</div>}
              <div className="grow">
                <h3>
                  {item.seq}. {item.title}
                </h3>
                <p className="muted">{item.location || "Location to be confirmed"}</p>
                <div className="row wrap">
                  <SeverityChip severity={item.severity} />
                  {item.owner && <span className="tag">{item.owner}</span>}
                  {item.due && <span className="tag">{item.due}</span>}
                  {item.needs_review.length > 0 && <span className="tag tag-warn">Needs review</span>}
                </div>
              </div>
            </Link>
          </li>
        ))}
      </ul>

      <nav className="bottombar">
        <Link to={`/w/${id}/add`} className="btn btn-primary grow btn-lg">
          <Icon name="camera" /> Add issue
        </Link>
        {insp.items.length > 0 && (
          <Link to={`/w/${id}/report`} className="btn btn-secondary btn-lg">
            <Icon name="file" /> Report
          </Link>
        )}
      </nav>
    </div>
  );
}
