import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "../api";
import { ErrorNote, Icon, SeverityChip, Spinner, TopBar } from "../components/ui";
import type { Inspection, Severity } from "../types";

async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through to the legacy path */
  }
  const area = document.createElement("textarea");
  area.value = text;
  area.style.position = "fixed";
  area.style.opacity = "0";
  document.body.appendChild(area);
  area.select();
  const ok = document.execCommand("copy");
  area.remove();
  return ok;
}

export default function Report() {
  const id = Number(useParams().id);
  const [insp, setInsp] = useState<Inspection | null>(null);
  const [actions, setActions] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [sharing, setSharing] = useState(false);

  useEffect(() => {
    api.get(id).then(setInsp).catch((e: Error) => setError(e.message));
    api.actions(id).then((a) => setActions(a.text)).catch(() => undefined);
  }, [id]);

  if (!insp) {
    return (
      <div className="page">
        <TopBar title="Report" back={`/w/${id}`} />
        <ErrorNote message={error} />
        {!error && <Spinner label="Loading…" />}
      </div>
    );
  }

  const count = (s: Severity) => insp.items.filter((i) => i.severity === s).length;
  const review = insp.items.filter((i) => i.needs_review.length > 0).length;
  const fileName = `Site_Report_${insp.site_name.replace(/[^A-Za-z0-9]+/g, "-")}.pdf`;

  const copy = async () => {
    setCopied(await copyText(actions));
    window.setTimeout(() => setCopied(false), 2200);
  };

  const share = async () => {
    setSharing(true);
    setError(null);
    try {
      const blob = await (await fetch(api.pdfUrl(id))).blob();
      const file = new File([blob], fileName, { type: "application/pdf" });
      if (navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: `Site inspection report: ${insp.site_name}`, text: actions });
      } else {
        window.location.href = api.pdfUrl(id, true);
      }
    } catch (e) {
      if ((e as Error).name !== "AbortError") setError("The report could not be shared. Use Download instead.");
    } finally {
      setSharing(false);
    }
  };

  return (
    <div className="page has-bottombar">
      <TopBar title="Report" subtitle={[insp.site_name, insp.area].filter(Boolean).join(" · ")} back={`/w/${id}`} />
      <ErrorNote message={error} />
      {review > 0 && (
        <p className="note note-warn">
          {review} {review === 1 ? "item still has" : "items still have"} unconfirmed fields. They appear as “To be confirmed” in the report.
        </p>
      )}

      <div className="tiles">
        <div className="tile">
          <span>Items</span>
          <strong>{insp.items.length}</strong>
        </div>
        <div className="tile">
          <span>High</span>
          <strong className="c-high">{count("High")}</strong>
        </div>
        <div className="tile">
          <span>Medium</span>
          <strong className="c-medium">{count("Medium")}</strong>
        </div>
        <div className="tile">
          <span>Low</span>
          <strong className="c-low">{count("Low")}</strong>
        </div>
      </div>

      <section className="card">
        <h2 className="label">PDF report</h2>
        <p className="muted">A formatted English report with a summary, one entry per issue with its photo, and an action table.</p>
        <div className="row wrap">
          <a className="btn btn-secondary grow" href={api.pdfUrl(id)} target="_blank" rel="noreferrer">
            <Icon name="file" /> Open
          </a>
          <a className="btn btn-secondary grow" href={api.pdfUrl(id, true)} download={fileName}>
            Download
          </a>
        </div>
      </section>

      <section className="card">
        <h2 className="label">Action items</h2>
        <ol className="actions">
          {insp.items.map((i) => (
            <li key={i.id}>
              <div>
                <strong>{i.title}</strong>
                <span className="muted">{[i.owner || "Owner to be confirmed", i.due || "Due to be confirmed"].join(" · ")}</span>
              </div>
              <SeverityChip severity={i.severity} />
            </li>
          ))}
        </ol>
        <button className="btn btn-secondary btn-block" onClick={copy} disabled={!actions}>
          <Icon name={copied ? "check" : "copy"} /> {copied ? "Copied to the clipboard" : "Copy action items"}
        </button>
        <p className="hint">With Office Kit, the phone and laptop share one clipboard, so the list can be pasted straight into an email or tracker on the laptop.</p>
      </section>

      <nav className="bottombar">
        <button className="btn btn-primary btn-block btn-lg" onClick={share} disabled={sharing}>
          {sharing ? "Preparing…" : "Send the report"}
        </button>
      </nav>
    </div>
  );
}
