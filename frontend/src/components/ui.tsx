import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import type { Health, Severity } from "../types";

export function Icon({ name, size = 22 }: { name: "back" | "camera" | "mic" | "check" | "plus" | "file" | "trash" | "copy" | "stop" | "refresh"; size?: number }) {
  const paths: Record<string, ReactNode> = {
    back: <path d="M15 5l-7 7 7 7" />,
    camera: (
      <>
        <path d="M4 8h3l2-3h6l2 3h3v11H4z" />
        <circle cx="12" cy="13" r="3.5" />
      </>
    ),
    mic: (
      <>
        <rect x="9" y="3" width="6" height="11" rx="3" />
        <path d="M5.5 11a6.5 6.5 0 0013 0M12 17.5V21" />
      </>
    ),
    check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
    plus: <path d="M12 5v14M5 12h14" />,
    file: (
      <>
        <path d="M7 3h7l4 4v14H7z" />
        <path d="M14 3v4h4M10 12h5M10 16h5" />
      </>
    ),
    trash: <path d="M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13" />,
    copy: (
      <>
        <rect x="8" y="8" width="11" height="12" rx="2" />
        <path d="M5 16V5a1 1 0 011-1h9" />
      </>
    ),
    stop: <rect x="7" y="7" width="10" height="10" rx="2" />,
    refresh: <path d="M19 12a7 7 0 11-2.2-5.1M19 4v4h-4" />,
  };
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

export function TopBar({ title, subtitle, back, right }: { title: string; subtitle?: string; back?: string; right?: ReactNode }) {
  return (
    <header className="topbar">
      {back && (
        <Link to={back} className="iconbtn" aria-label="Back">
          <Icon name="back" />
        </Link>
      )}
      <div className="topbar-text">
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {right}
    </header>
  );
}

export function SeverityChip({ severity }: { severity: Severity }) {
  return <span className={`chip sev-${severity.toLowerCase()}`}>{severity}</span>;
}

export function StatusPill({ health }: { health: Health | null }) {
  if (!health) return <span className="pill">Checking…</span>;
  const speech = health.speech.enabled && !health.speech.error;
  const llm = health.llm.model_ready;
  return (
    <span className="pill pill-ok" title={`Speech model: ${health.speech.model}. Structuring: ${llm ? health.llm.model : "built-in rules"}.`}>
      <span className="dot" />
      {speech ? "Voice on" : "Voice off"} · {llm ? "Local model" : "Rules"}
    </span>
  );
}

export function ErrorNote({ message }: { message: string | null }) {
  return message ? (
    <p className="note note-error" role="alert">
      {message}
    </p>
  ) : null;
}

export function Spinner({ label }: { label: string }) {
  return (
    <div className="spinner-row" role="status">
      <span className="spinner" />
      <span>{label}</span>
    </div>
  );
}
