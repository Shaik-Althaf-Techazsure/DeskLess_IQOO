import type { Health, Inspection, InspectionSummary, Item, Severity } from "./types";

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api${path}`, init);
  } catch {
    throw new Error("Cannot reach the DeskLess server. Check that it is running and that you are on the same network.");
  }
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = await res.json();
      detail = typeof body.detail === "string" ? body.detail : JSON.stringify(body.detail);
    } catch {
      /* keep the status text */
    }
    throw new Error(detail || `Request failed (${res.status})`);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

export interface ItemPatch {
  title?: string;
  location?: string;
  severity?: Severity;
  owner?: string;
  due?: string;
}

export const api = {
  health: () => req<Health>("/health"),
  list: () => req<InspectionSummary[]>("/inspections"),
  create: (body: { site_name: string; area: string; inspector: string }) => req<Inspection>("/inspections", json("POST", body)),
  get: (id: number) => req<Inspection>(`/inspections/${id}`),
  remove: (id: number) => req<void>(`/inspections/${id}`, { method: "DELETE" }),
  demo: () => req<Inspection>("/demo", { method: "POST" }),
  item: (id: number) => req<Item>(`/items/${id}`),
  addItem: (inspectionId: number, input: { photo?: Blob | null; audio?: Blob | null; note?: string }) => {
    const form = new FormData();
    if (input.photo) form.append("photo", input.photo, "photo.jpg");
    if (input.audio) {
      const ext = input.audio.type.includes("mp4") ? "m4a" : input.audio.type.includes("ogg") ? "ogg" : "webm";
      form.append("audio", input.audio, (input.audio as File).name || `note.${ext}`);
    }
    if (input.note?.trim()) form.append("note", input.note.trim());
    return req<Item>(`/inspections/${inspectionId}/items`, { method: "POST", body: form });
  },
  updateItem: (id: number, patch: ItemPatch) => req<Item>(`/items/${id}`, json("PATCH", patch)),
  reprocess: (id: number) => req<Item>(`/items/${id}/reprocess`, { method: "POST" }),
  deleteItem: (id: number) => req<void>(`/items/${id}`, { method: "DELETE" }),
  actions: (id: number) => req<{ text: string; count: number }>(`/inspections/${id}/actions`),
  pdfUrl: (id: number, download = false) => `/api/inspections/${id}/report.pdf${download ? "?download=true" : ""}`,
};

export function engineLabel(engine: string): string {
  if (engine.startsWith("llm:")) return `Local model (${engine.slice(4)})`;
  if (engine === "rules") return "Built-in rules";
  return "Entered by hand";
}
