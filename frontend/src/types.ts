export type Severity = "High" | "Medium" | "Low";

export interface Item {
  id: number;
  inspection_id: number;
  seq: number;
  photo_url: string | null;
  thumb_url: string | null;
  audio_url: string | null;
  note_text: string | null;
  transcript: string | null;
  transcript_en: string | null;
  language: string | null;
  title: string;
  location: string | null;
  severity: Severity;
  owner: string | null;
  due: string | null;
  due_date: string | null;
  engine: string;
  needs_review: string[];
  created_at: string;
  warnings: string[];
}

export interface InspectionSummary {
  id: number;
  site_name: string;
  area: string;
  inspector: string;
  created_at: string;
  item_count: number;
  high_count: number;
}

export interface Inspection extends InspectionSummary {
  items: Item[];
}

export interface Health {
  status: string;
  database: boolean;
  speech: { enabled: boolean; model: string; loaded: boolean; error: string | null };
  llm: { configured: boolean; model: string; reachable: boolean; model_ready: boolean; vision: boolean };
}
