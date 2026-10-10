"use strict";

// One page for every role. After signing in, the server says who the person is, and the page shows the
// sections, numbers and actions that belong to that role. What each may see is decided by the server; the
// page only decides how to show it. The overview keeps itself up to date every few seconds, in place.

const $ = (id) => document.getElementById(id);
const store = sessionStorage;
// The page may be served under a path on its domain; the API is beside the page, wherever that is.
const BASE = new URL(".", location.href).pathname;
const calm = matchMedia("(prefers-reduced-motion: reduce)").matches;

let token = store.getItem("deskless-token") || "";
let dash = null;            // what the server last said about this person's numbers
let view = "";              // the section that is open; a role's first until one is chosen
let board = null;           // the live widgets of the open section, kept up to date in place
let issues = [];
let issueFilter = "";
let issueSearch = "";
let ofReport = null;        // when set, only the issues of this report are listed
let peopleStage = "approved";
let peopleSearch = "";
let peopleRole = "";
let reportSearch = "";

const NAV = {
  admin: [["overview", "Control room", "grid"], ["approvals", "Approvals", "check"], ["companies", "Companies", "building"], ["people", "People", "users"], ["reports", "Reports", "file"], ["issues", "Issues", "alert"], ["system", "System", "pulse"]],
  md: [["overview", "Overview", "grid"], ["approvals", "Approvals", "check"], ["issues", "Issues", "alert"], ["reports", "Reports", "file"], ["people", "People", "users"]],
  manager: [["overview", "Overview", "grid"], ["approvals", "Approvals", "check"], ["issues", "Issues", "alert"], ["reports", "Reports", "file"], ["people", "My team", "users"]],
  inspector: [["overview", "Overview", "grid"], ["reports", "My reports", "file"], ["issues", "My issues", "alert"]],
  technical: [["issues", "My jobs", "wrench"], ["overview", "Overview", "grid"]],
};
const ICONS = {
  grid: "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z",
  check: "M9 12l2 2 4-4M12 21a9 9 0 100-18 9 9 0 000 18z",
  building: "M4 21V6l8-3v18M12 9h8v12M8 8v.01M8 12v.01M8 16v.01M16 13v.01M16 17v.01M2 21h20",
  users: "M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2M9 11a4 4 0 100-8 4 4 0 000 8zM22 21v-2a4 4 0 00-3-3.9M16 3.1a4 4 0 010 7.8",
  file: "M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8zM14 2v6h6M8 13h8M8 17h5",
  alert: "M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z",
  pulse: "M22 12h-4l-3 9L9 3l-3 9H2",
  wrench: "M14.7 6.3a4 4 0 00-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 005.4-5.4l-2.5 2.5-2.5-2.5z",
};
const STATUS = { open: "To give out", assigned: "With technician", fixed: "Fixed, to check", closed: "Closed" };
const STAGES = [["open", "To give out", "var(--stage-open)"], ["assigned", "With technicians", "var(--stage-assigned)"], ["fixed", "Fixed, to check", "var(--stage-fixed)"], ["closed", "Closed", "var(--stage-closed)"]];
const SEVERITY = { CRITICAL: "Critical", HIGH: "High", MEDIUM: "Medium", LOW: "Low" };
const SEVERITY_COLOUR = { Critical: "var(--critical)", High: "var(--serious)", Medium: "var(--warning)", Low: "var(--rest)" };
const SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];
const CHECK = {
  MATCH: "The picture matches the note",
  CONFIRMED: "The AI was unsure; the inspector confirmed the picture on site",
  UNSURE: "The AI was unsure about the picture",
  MISMATCH: "The AI thinks the picture may not match the note",
};
const VERDICT = { GENUINE: ["good", "AI review: genuine"], DOUBTFUL: ["warn", "AI review: doubtful"], FAKE: ["bad", "AI review: fake"] };

// ---------------------------------------------------------------- small helpers

// Builds an element. Text always goes in as text, never as HTML, so nothing a person typed can run.
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node[key] = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) if (child != null && child !== false && child !== "") node.append(child);
  return node;
}

// The same for the drawn parts of a chart.
function svg(tag, attrs = {}, ...children) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (key === "text") node.textContent = value;
    else node.setAttribute(key, value);
  }
  for (const child of children.flat()) if (child) node.append(child);
  return node;
}
const icon = (name) => svg("svg", { viewBox: "0 0 24 24", class: "icon", "aria-hidden": "true" }, svg("path", { d: ICONS[name] }));

async function api(path, options = {}) {
  const headers = token ? { Authorization: "Bearer " + token } : {};
  let body = options.body;
  if (body && !(body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(BASE + "api" + path, { method: options.method || (body ? "POST" : "GET"), headers, body });
  } catch {
    throw new Error("The server cannot be reached. Check the connection.");
  }
  if (response.status === 401 && token && !options.signingIn) {
    signOut();
    throw new Error("Sign in again.");
  }
  if (!response.ok) throw new Error((await response.json().catch(() => ({}))).detail || "Something went wrong.");
  return response;
}
const get = (path) => api(path).then((response) => response.json());
const post = (path, body = {}) => api(path, { method: "POST", body }).then((response) => response.json());

function when(iso) {
  return iso ? new Date(iso).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "";
}
function day(date) {
  if (!date) return "";
  const [year, month, dayOfMonth] = date.split("-").map(Number);
  return new Date(year, month - 1, dayOfMonth).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}
function ago(iso) {
  const seconds = Math.round((Date.now() - new Date(iso)) / 1000);
  if (seconds < 45) return "just now";
  if (seconds < 3600) return Math.max(1, Math.round(seconds / 60)) + " min ago";
  if (seconds < 86400) return Math.round(seconds / 3600) + " h ago";
  return when(iso);
}
function lasting(seconds) {
  if (seconds < 90) return seconds + " s";
  if (seconds < 5400) return Math.round(seconds / 60) + " min";
  if (seconds < 172800) return Math.round(seconds / 3600) + " h";
  return Math.round(seconds / 86400) + " days";
}
function weight(bytes) {
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1048576) return (bytes / 1024).toFixed(0) + " KB";
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + " MB";
  return (bytes / 1073741824).toFixed(1) + " GB";
}
const count = (n, one, many) => `${n} ${n === 1 ? one : many || one + "s"}`;

// What just happened, said at the foot of the page for a few seconds.
let noteTimer;
function note(text, bad = false) {
  $("note").textContent = text;
  $("note").className = bad ? "bad" : "";
  $("note").hidden = false;
  clearTimeout(noteTimer);
  noteTimer = setTimeout(() => { $("note").hidden = true; }, bad ? 9000 : 6000);
}

// Pictures need the sign-in, so they are fetched rather than linked. Those in a list are fetched only once
// they scroll into sight; one that can be opened large is wanted now.
const fetched = new Map();
function pictureUrl(path) {
  if (!fetched.has(path)) {
    fetched.set(path, api(path).then((response) => response.blob()).then((blob) => URL.createObjectURL(blob)).catch(() => { fetched.delete(path); return ""; }));
  }
  return fetched.get(path);
}
const inSight = new IntersectionObserver((entries) => {
  for (const entry of entries) {
    if (!entry.isIntersecting) continue;
    inSight.unobserve(entry.target);
    fill(entry.target);
  }
}, { rootMargin: "300px" });
function fill(img) {
  pictureUrl(img.dataset.path).then((url) => { if (url) { img.src = url; img.alt = img.dataset.alt; } });
}
function picture(path, alt, zoom = false) {
  const img = el("img", { alt: "", "data-path": path, "data-alt": alt });
  if (zoom) {
    fill(img);
    img.onclick = () => { if (img.src) { $("big").querySelector("img").src = img.src; $("big").showModal(); } };
  } else {
    inSight.observe(img);
  }
  return img;
}

// A phone's camera picture is far larger than a repair needs; it is made smaller before it is sent.
async function smaller(file, edge = 1600) {
  try {
    const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    const scale = Math.min(1, edge / Math.max(bitmap.width, bitmap.height));
    const canvas = el("canvas", { width: Math.round(bitmap.width * scale), height: Math.round(bitmap.height * scale) });
    canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise((done) => canvas.toBlob(done, "image/jpeg", 0.85));
    return blob || file;
  } catch {
    return file;
  }
}

// Hands the rows on screen to the person as a spreadsheet file.
function download(name, columns, rows) {
  const cell = (value) => {
    let text = value == null ? "" : String(value);
    // A spreadsheet would run a cell that starts like a formula; a leading apostrophe makes it plain text.
    if (/^[=+\-@]/.test(text)) text = "'" + text;
    return /[",\n\r]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
  };
  const lines = [columns.map(([title]) => title), ...rows.map((row) => columns.map(([, from]) => (typeof from === "function" ? from(row) : row[from])))];
  const url = URL.createObjectURL(new Blob(["﻿" + lines.map((line) => line.map(cell).join(",")).join("\r\n") + "\r\n"], { type: "text/csv" }));
  el("a", { href: url, download: name }).click();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

const chip = (text, kind = "") => el("span", { class: "chip " + kind, text });
const statusChip = (issue) => chip(dash.me.role === "technical" && issue.status === "assigned" ? "To do" : STATUS[issue.status] || issue.status, issue.status);

// ---------------------------------------------------------------- the parts every chart shares

// One small box follows the pointer and says what is under it. Values lead, names follow.
const tip = el("div", { id: "tip", role: "tooltip", hidden: true });
document.body.append(tip);
function showTip(at, ...content) {
  tip.replaceChildren(...content);
  tip.hidden = false;
  const box = tip.getBoundingClientRect();
  let x = at.clientX + 14;
  let y = at.clientY + 14;
  if (x + box.width > innerWidth - 8) x = at.clientX - box.width - 14;
  if (y + box.height > innerHeight - 8) y = at.clientY - box.height - 14;
  tip.style.left = Math.max(8, x) + "px";
  tip.style.top = Math.max(8, y) + "px";
}
const hideTip = () => { tip.hidden = true; };
const tipTitle = (text) => el("div", { class: "tip-title", text });
const tipRow = (colour, value, label) => el("div", { class: "tip-row" }, el("i", { class: "key", style: `background:${colour}` }), el("b", { text: value }), el("span", { text: label }));
// The same words for a pointer over a mark and for the keyboard on it.
function hoverTip(node, content) {
  node.addEventListener("pointermove", (event) => showTip(event, ...content()));
  node.addEventListener("pointerleave", hideTip);
  node.addEventListener("focus", () => { const box = node.getBoundingClientRect(); showTip({ clientX: box.left + box.width / 2, clientY: box.bottom - 10 }, ...content()); });
  node.addEventListener("blur", hideTip);
}
const key = (shape, colour, label) => el("span", {}, el("i", { class: "key " + shape, style: `background:${colour}` }), label);

// A number that counts its way to a new value instead of jumping.
function countTo(node, to, show = (n) => Math.round(n).toLocaleString()) {
  const from = node.reached ?? 0;
  node.reached = to;
  cancelAnimationFrame(node.frame);
  if (calm || from === to) { node.textContent = show(to); return; }
  const began = performance.now();
  const step = (now) => {
    const part = Math.min(1, (now - began) / 700);
    node.textContent = show(from + (to - from) * (1 - Math.pow(1 - part, 3)));
    if (part < 1) node.frame = requestAnimationFrame(step);
  };
  node.frame = requestAnimationFrame(step);
}

// A round number just above the largest value, so the gridlines read 0, half, all.
function roundAbove(n) {
  if (n <= 4) return 4;
  const tens = Math.pow(10, Math.floor(Math.log10(n)));
  return [1, 2, 4, 6, 8, 10].map((m) => m * tens).find((top) => top >= n);
}

// The last fortnight in a tile's corner: a quiet line, with today marked.
function sparkline(values) {
  const top = Math.max(1, ...values);
  const x = (i) => 3 + (94 * i) / Math.max(1, values.length - 1);
  const y = (v) => 26 - (22 * v) / top;
  return svg("svg", { viewBox: "0 0 100 30", preserveAspectRatio: "none", "aria-hidden": "true" },
    svg("polyline", { points: values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" "), fill: "none", stroke: "var(--rest)", "stroke-width": 1.5, "stroke-linejoin": "round", "vector-effect": "non-scaling-stroke" }),
    // The mark for today is a line of no length with round ends: it stays a round dot however the tile stretches the drawing.
    svg("line", { x1: x(values.length - 1), x2: x(values.length - 1), y1: y(values[values.length - 1]), y2: y(values[values.length - 1]), stroke: "var(--series-1)", "stroke-width": 6, "stroke-linecap": "round", "vector-effect": "non-scaling-stroke" }));
}

// One number with what it is, how today compares with yesterday, and the fortnight behind it.
function statTile({ label, go: open, upIsGood }) {
  const value = el("b", { class: "value", text: "0" });
  const delta = el("span", { class: "delta" });
  const small = el("span");
  const spark = el("div", { class: "spark" });
  const node = el(open ? "button" : "div", { class: "tile card", onclick: open }, el("span", { class: "label", text: label }), value, el("div", { class: "under" }, delta, small), spark);
  let last = null;
  return {
    node,
    set(n, { today, yesterday, series, note: words = "", tone = "" } = {}) {
      countTo(value, n);
      if (last !== null && last !== n && !calm) {
        node.classList.remove("changed");
        void node.offsetWidth;                 // lets the highlight play again
        node.classList.add("changed");
      }
      last = n;
      node.dataset.tone = tone;
      if (today != null) {
        const change = today - (yesterday || 0);
        const good = upIsGood == null || change === 0 ? "" : (change > 0) === upIsGood ? " good" : " bad";
        delta.className = "delta" + good;
        delta.textContent = `${change > 0 ? "▲" : change < 0 ? "▼" : "●"} ${today} today`;
        delta.title = `${today} today, ${yesterday || 0} yesterday`;
        small.textContent = change ? `${change > 0 ? "+" : "−"}${Math.abs(change)} on yesterday` : "same as yesterday";
      } else {
        delta.textContent = "";
        small.textContent = words;
      }
      if (series) spark.replaceChildren(sparkline(series));
    },
  };
}

// Lines over time. Every value is also in the table behind the "Table" button; the pointer or the arrow
// keys move a hairline that reads out all lines at one moment.
function lineChart({ series, label, title, name, tall = false }) {
  const W = 640, H = tall ? 272 : 232, L = 34, R = 30, T = 14, B = 28;
  const plot = svg("svg", { viewBox: `0 0 ${W} ${H}`, class: "plot", tabindex: 0, role: "img", "aria-label": name });
  const cross = svg("g", { class: "cross" });
  const table = el("table", { class: "twin", hidden: true });
  let rows = [], first = true, at = -1, x = () => 0, y = () => 0, drawn = "";

  function place(i, event) {
    at = Math.max(0, Math.min(rows.length - 1, i));
    const row = rows[at];
    cross.replaceChildren(svg("line", { x1: x(at), x2: x(at), y1: T, y2: H - B }), ...series.map((one) => svg("circle", { cx: x(at), cy: y(row[one.key]), r: 4, fill: one.colour })));
    cross.style.display = "";
    const content = [tipTitle(title(row)), ...series.map((one) => tipRow(one.colour, row[one.key], one.label))];
    const box = plot.getBoundingClientRect();
    showTip(event || { clientX: box.left + (x(at) / W) * box.width, clientY: box.top + box.height / 3 }, ...content);
  }
  function leave() { cross.style.display = "none"; hideTip(); at = -1; }
  plot.onpointermove = (event) => {
    if (!rows.length) return;
    const box = plot.getBoundingClientRect();
    place(Math.round(((((event.clientX - box.left) / box.width) * W - L) / (W - L - R)) * (rows.length - 1)), event);
  };
  plot.onpointerleave = leave;
  plot.onblur = leave;
  plot.onfocus = () => { if (rows.length) place(rows.length - 1); };
  plot.onkeydown = (event) => {
    if (event.key === "ArrowLeft") place(at - 1);
    else if (event.key === "ArrowRight") place(at + 1);
    else if (event.key === "Escape") leave();
    else return;
    event.preventDefault();
  };

  function draw() {
    const top = roundAbove(Math.max(1, ...rows.flatMap((row) => series.map((one) => row[one.key]))));
    x = (i) => L + (W - L - R) * (rows.length > 1 ? i / (rows.length - 1) : 0.5);
    y = (v) => H - B - (H - T - B) * (v / top);
    const path = (k) => rows.map((row, i) => `${i ? "L" : "M"}${x(i).toFixed(1)} ${y(row[k]).toFixed(1)}`).join(" ");
    const last = rows.length - 1;
    const every = Math.max(1, Math.ceil(rows.length / 8));
    const ends = series.map((one) => y(rows[last][one.key]));
    plot.replaceChildren(
      ...[0, top / 2, top].map((v) => svg("g", {}, svg("line", { x1: L, x2: W - R, y1: y(v), y2: y(v), class: v ? "grid" : "axis" }), svg("text", { x: L - 8, y: y(v) + 4, class: "tick", "text-anchor": "end", text: v }))),
      ...rows.map((row, i) => ((last - i) % every === 0 ? svg("text", { x: x(i), y: H - 8, class: "tick", "text-anchor": "middle", text: label(row) }) : null)),
      ...series.filter((one) => one.wash).map((one) => svg("path", { d: `${path(one.key)} L${x(last).toFixed(1)} ${y(0)} L${x(0).toFixed(1)} ${y(0)} Z`, class: "wash", style: `fill:${one.colour}` })),
      ...series.map((one) => svg("path", { d: path(one.key), class: "series" + (first ? " draw" : ""), stroke: one.colour, pathLength: 1 })),
      ...series.map((one, n) => svg("circle", { cx: x(last), cy: ends[n], r: 4, fill: one.colour, class: "end" })),
      // The newest value is written beside its line. Where two lines end together only the first is;
      // the legend, the hairline and the table carry the other.
      ...series.map((one, n) => (n === 0 || Math.abs(ends[n] - ends[0]) >= 14 ? svg("text", { x: x(last) + 8, y: ends[n] + 4, class: "endlabel", text: rows[last][one.key] }) : null)),
      cross);
    cross.style.display = "none";
    first = false;
    table.replaceChildren(
      el("tr", {}, el("th", { text: "When" }), ...series.map((one) => el("th", { class: "num", text: one.label }))),
      ...rows.map((row) => el("tr", {}, el("td", { text: title(row) }), ...series.map((one) => el("td", { class: "num", text: row[one.key] })))));
  }
  return {
    plot, table,
    update(next) {
      const now = JSON.stringify(next);
      if (now === drawn || !next.length) return;
      drawn = now;
      rows = next;
      draw();
    },
  };
}

function chartCard(heading, chart, legend, width = "") {
  const swap = el("button", { class: "small quiet", text: "Table", "aria-pressed": "false" });
  swap.onclick = () => {
    const toTable = chart.table.hidden;
    chart.table.hidden = !toTable;
    chart.plot.style.display = toTable ? "none" : "";
    swap.setAttribute("aria-pressed", String(toTable));
    swap.textContent = toTable ? "Chart" : "Table";
  };
  return el("div", { class: "card chart " + width }, el("div", { class: "head" }, el("h4", { text: heading }), legend, swap), chart.plot, el("div", { class: "scroll" }, chart.table));
}

// Bars in a list, longest need first. A second, grey part can show what is already closed beside what is not.
function barList({ stacked = false, colour, show = (n) => n, word = "", wide = false } = {}) {
  const box = el("div", { class: wide ? "bars wide" : "bars" });
  const nothing = el("div", { class: "hint", text: "Nothing to show yet." });
  const known = new Map();
  return {
    node: box,
    update(rows) {
      const most = Math.max(1, ...rows.map((row) => row.total));
      const names = new Set(rows.map((row) => row.name));
      for (const [name, item] of known) if (!names.has(name)) { item.line.remove(); known.delete(name); }
      rows.forEach((row, at) => {
        let item = known.get(row.name);
        if (!item) {
          const item_ = { data: row, colour: "var(--series-1)" };
          item_.fill = el("i", { class: "fill" });
          item_.rest = stacked ? el("i", { class: "fill rest" }) : null;
          item_.n = el("span", { class: "count" });
          item_.line = el("div", { class: "bar", tabindex: 0 }, el("span", { class: "name", text: row.name, title: row.name }), el("div", { class: "track" }, item_.fill, item_.rest), item_.n);
          hoverTip(item_.line, () => stacked
            ? [tipTitle(item_.data.name), tipRow("var(--series-1)", item_.data.open, "not closed"), tipRow("var(--rest)", item_.data.total - item_.data.open, "closed")]
            : [tipRow(item_.colour, show(item_.data.open), `${item_.data.name}${word}`)]);
          known.set(row.name, item_);
          item = item_;
        }
        item.data = row;
        item.colour = (colour && colour(row)) || "var(--series-1)";
        if (box.children[at] !== item.line) box.insertBefore(item.line, box.children[at] || null);
        item.fill.style.background = item.colour;
        const closed = row.total - row.open;
        // Only the outer end of a bar is rounded; where the grey part follows, the first ends square.
        item.fill.style.borderRadius = stacked && closed ? "0" : "";
        requestAnimationFrame(() => {
          item.fill.style.width = `${(100 * row.open) / most}%`;
          if (item.rest) item.rest.style.width = `${(100 * closed) / most}%`;
        });
        item.n.textContent = show(row.open);
      });
      if (rows.length) nothing.remove(); else box.append(nothing);
    },
  };
}

// Where every issue stands: one bar divided by stage, the counts beside their names, and the share closed.
function stageBar() {
  const round = 2 * Math.PI * 32;
  const parts = {}, counts = {};
  const bar = el("div", { class: "bar" });
  const list = el("ul");
  for (const [stage, label, colour] of STAGES) {
    const words = stage === "open" && !dash.may_direct ? "Not given out yet" : label;
    const part = el("button", { class: "seg", style: `background:${colour}`, "aria-label": words, hidden: true, onclick: () => go("issues", stage) });
    hoverTip(part, () => [tipRow(colour, part.dataset.n, words)]);
    parts[stage] = part;
    bar.append(part);
    counts[stage] = el("b", { text: "0" });
    list.append(el("li", {}, el("button", { onclick: () => go("issues", stage) }, el("i", { class: "key square", style: `background:${colour}` }), el("span", { text: words }), counts[stage])));
  }
  const arc = svg("circle", { class: "fill", cx: 40, cy: 40, r: 32, "stroke-dasharray": round, "stroke-dashoffset": round });
  const share = el("b", { text: "0%" });
  const ring = el("div", { class: "ring" },
    svg("svg", { viewBox: "0 0 80 80", "aria-hidden": "true" }, svg("circle", { class: "track", cx: 40, cy: 40, r: 32 }), arc),
    el("div", {}, share, el("span", { text: "of all issues are closed" })));
  return {
    node: el("div", { class: "card panel stages w4" }, el("div", { class: "head" }, el("h4", { text: "Where every issue stands" })), bar, list, ring),
    update(t) {
      for (const [stage] of STAGES) {
        parts[stage].hidden = !t[stage];
        parts[stage].dataset.n = t[stage];
        requestAnimationFrame(() => { parts[stage].style.flexGrow = t[stage]; });
        countTo(counts[stage], t[stage]);
      }
      const closed = t.issues ? t.closed / t.issues : 0;
      arc.setAttribute("stroke-dashoffset", round * (1 - closed));
      countTo(share, Math.round(closed * 100), (n) => Math.round(n) + "%");
    },
  };
}

// What is happening, newest first. Something new slides in at the top.
function feed() {
  const list = el("div", { class: "feed" });
  const nothing = el("div", { class: "hint", text: "Nothing has happened yet." });
  const known = new Map();
  return {
    node: list,
    update(events) {
      const later = known.size > 0;
      for (const event of [...events].reverse()) {
        if (known.has(event.id)) continue;
        const time = el("time");
        const inside = [el("span", { class: "dot " + event.kind }), el("span", {}, event.about ? el("em", { text: event.about + " · " }) : null, event.text), time];
        const line = event.issue_id && event.about
          ? el("button", { class: "line", onclick: () => openIssue(event.issue_id) }, ...inside)
          : el("div", { class: "line" }, ...inside);
        if (later) line.classList.add("new");
        list.prepend(line);
        known.set(event.id, { line, time, at: event.at });
      }
      const kept = new Set(events.map((event) => event.id));
      for (const [id, item] of known) if (!kept.has(id)) { item.line.remove(); known.delete(id); }
      for (const item of known.values()) item.time.textContent = ago(item.at);
      if (known.size) nothing.remove(); else list.append(nothing);
    },
  };
}

// Who is using DeskLess right now.
function onlineList() {
  const list = el("div", { class: "online" });
  const nothing = el("div", { class: "hint", text: "Nobody has used DeskLess in the last quarter of an hour." });
  const known = new Map();
  return {
    node: list,
    update(people) {
      const here = new Set(people.map((one) => one.id));
      for (const [id, item] of known) if (!here.has(id)) { item.row.remove(); known.delete(id); }
      people.forEach((one, at) => {
        let item = known.get(one.id);
        if (!item) {
          item = { time: el("time"), light: el("i") };
          item.row = el("div", { class: "one" },
            el("div", { class: "face" }, picture(`/people/${one.id}/photo`, "Photo of " + one.name), item.light),
            el("div", {}, el("strong", { text: one.name }), el("span", { text: [one.role_name, dash.me.role === "admin" ? one.organisation : ""].filter(Boolean).join(" · ") })),
            item.time);
          known.set(one.id, item);
        }
        if (list.children[at] !== item.row) list.insertBefore(item.row, list.children[at] || null);
        const fresh = Date.now() - new Date(one.seen_at) < 150000;
        item.light.className = fresh ? "now" : "";
        item.time.textContent = fresh ? "now" : ago(one.seen_at);
      });
      if (people.length) nothing.remove(); else list.append(nothing);
    },
  };
}

// The health of the server itself.
function healthList() {
  const state = (words, kind = "") => el("span", { class: "state " + kind }, el("i"), words);
  const list = el("div", { class: "health" });
  const used = el("i");
  let drawn = "";
  return {
    node: list,
    update(sys) {
      const mark = JSON.stringify([sys.database, sys.mail, sys.storage, sys.last_report_at, Math.round(sys.uptime_seconds / 30), sys.requests.total, sys.requests.errors]);
      if (mark === drawn) return;
      drawn = mark;
      const files = sys.storage.reports + sys.storage.pictures + sys.storage.photos;
      const disk = sys.storage.disk_total ? 1 - sys.storage.disk_free / sys.storage.disk_total : 0;
      const entry = (name, value) => el("div", { class: "entry" }, el("span", { text: name }), value);
      list.replaceChildren(...[
        entry("Server", state("Up for " + lasting(sys.uptime_seconds))),
        entry("Database", sys.database.ok ? state(`Answers in ${sys.database.ms} ms`) : state("Not answering", "bad")),
        entry("Mail server", sys.mail ? state("Set: codes are emailed") : state("Not set: codes are not emailed", "warn")),
        entry("Requests, last hour", state(`${sys.requests.total.toLocaleString()} · typically ${sys.requests.typical_ms} ms`)),
        sys.requests.errors ? entry("Errors, last hour", state(String(sys.requests.errors), "bad")) : null,
        entry("Last report received", el("span", { class: "state", text: sys.last_report_at ? ago(sys.last_report_at) : "none yet" })),
        el("div", { class: "entry" }, el("div", { class: "wide" },
          el("span", { text: `Storage: ${weight(files)} of reports and pictures · the disk is ${Math.round(disk * 100)}% full` }),
          el("div", { class: "meter" }, used))),
      ].filter(Boolean));
      requestAnimationFrame(() => { used.style.width = `${Math.max(1, Math.round(disk * 100))}%`; });
    },
  };
}

// A table that is only drawn again when what it shows has changed.
function liveTable(columns) {
  const table = el("table");
  let drawn = "";
  return {
    node: el("div", { class: "scroll" }, table),
    update(rows) {
      const now = JSON.stringify(rows);
      if (now === drawn) return;
      drawn = now;
      table.replaceChildren(
        el("tr", {}, ...columns.map((column) => el("th", { class: column.num ? "num" : "", text: column.title }))),
        ...rows.map((row) => el("tr", {}, ...columns.map((column) => {
          const value = column.value(row);
          return el("td", { class: (column.num ? "num " : "") + (column.late && column.late(row) ? "late" : "") }, value instanceof Node ? value : String(value));
        }))));
    },
  };
}
function closedMeter(row) {
  const share = row.issues ? Math.round((100 * (row.issues - row.open_issues)) / row.issues) : 0;
  return el("div", { title: `${share}% of ${row.issues} closed` }, el("div", { class: "meter done" }, el("i", { style: `width:${share}%` })));
}

// ---------------------------------------------------------------- signing in and out

function signOut() {
  token = "";
  dash = null;
  board = null;
  view = "";
  store.removeItem("deskless-token");
  if ($("sheet").open) $("sheet").close();
  show();
}

$("signin-form").onsubmit = async (event) => {
  event.preventDefault();
  $("signin-error").textContent = "";
  const typed = $("id").value.trim();
  const password = $("password").value;
  try {
    // The administrator is the one person who signs in with an email address.
    const answer = await api(typed.includes("@") ? "/admin/login" : "/login", {
      body: typed.includes("@") ? { email: typed, password } : { unique_id: typed, password },
      signingIn: true,
    }).then((response) => response.json());
    token = answer.token;
    store.setItem("deskless-token", token);
    $("password").value = "";
    await show();
  } catch (error) {
    $("signin-error").textContent = error.message;
  }
};
$("signout").onclick = signOut;
$("big").onclick = () => $("big").close();

// The app opens this page with a link that works once, so the person does not sign in a second time.
async function openedFromTheApp() {
  const code = new URLSearchParams(location.hash.slice(1)).get("code");
  if (!code) return;
  history.replaceState(null, "", location.pathname);
  try {
    const answer = await api("/web-link/open", { body: { code }, signingIn: true }).then((response) => response.json());
    token = answer.token;
    store.setItem("deskless-token", token);
  } catch (error) {
    $("signin-error").textContent = error.message;
  }
}

async function show() {
  if (token) {
    try {
      dash = await get("/dashboard");
    } catch (error) {
      if (token) $("signin-error").textContent = error.message;
    }
  }
  const inside = Boolean(token && dash);
  $("signin").hidden = inside;
  $("shell").hidden = !inside;
  if (!inside) return;
  $("company").textContent = dash.me.organisation;
  $("who-name").textContent = dash.me.name;
  $("who-role").textContent = dash.me.role === "admin" ? dash.me.email : [dash.me.role_name, dash.me.unique_id].filter(Boolean).join(" · ");
  if (!NAV[dash.me.role].some(([section]) => section === view)) view = NAV[dash.me.role][0][0];
  await load();
}

let navDrawn = "";
function drawNav() {
  const tiles = dash.tiles;
  const role = dash.me.role;
  // The number beside a section is what waits there for this person to act on.
  const waiting = {
    approvals: tiles.waiting_people,
    issues: role === "technical" ? tiles.my_jobs : dash.may_direct ? tiles.open + tiles.fixed : 0,
  };
  const now = JSON.stringify([role, view, waiting]);
  if (now === navDrawn) return;
  navDrawn = now;
  $("nav").replaceChildren(...NAV[role].map(([section, label, sign]) =>
    el("button", { class: section === view ? "on" : "", onclick: () => go(section) }, icon(sign), label, waiting[section] ? el("span", { class: "n", text: waiting[section] }) : null)));
}

function go(to, filter) {
  view = to;
  if (to !== "issues" || filter !== undefined) ofReport = null;
  if (filter !== undefined) issueFilter = filter;
  window.scrollTo(0, 0);
  load();
}

function heading(title, sub = "") {
  $("page-title").textContent = title;
  $("page-sub").textContent = sub;
}

// ---------------------------------------------------------------- loading a section, and keeping it live

// Everything the live sections draw from, asked for together.
async function liveData() {
  const fresh = await get("/dashboard");
  const more = {};
  if (fresh.me.role === "admin") [more.companies, more.system] = await Promise.all([get("/admin/companies"), get("/admin/system")]);
  return [fresh, more];
}

let loading = 0;
let lastHeard = 0;
let heard = true;
async function load() {
  const mine = ++loading;
  try {
    const [fresh, more] = await liveData();
    dash = fresh;
    let content;
    board = null;
    if (view === "overview") board = overviewBoard();
    else if (view === "system") board = systemBoard();
    else if (view === "approvals") content = approvals(await get("/people?status=pending_approval"));
    else if (view === "people") content = people(await get("/people?status=" + peopleStage));
    else if (view === "companies") content = companies(more.companies);
    else if (view === "reports") content = reports(await get("/reports"));
    else {
      issues = await get("/issues" + (ofReport ? "?report=" + encodeURIComponent(ofReport.id) : ""));
      content = issuesView();
    }
    if (mine !== loading) return;     // a newer request has overtaken this one
    drawNav();
    $("view").replaceChildren(...(board ? [board.node] : content));
    if (board) board.update(dash, more);
    $("live").hidden = !board;
    lastHeard = Date.now();
    heard = true;
  } catch (error) {
    if (token) note(error.message, true);
  }
}

// Brings the open live section up to date without drawing it again: numbers count on, bars slide, new
// events arrive at the top.
async function refresh() {
  try {
    const [fresh, more] = await liveData();
    dash = fresh;
    drawNav();
    if (board) board.update(fresh, more);
    lastHeard = Date.now();
    heard = true;
  } catch {
    heard = false;
  }
}

let beats = 0;
setInterval(() => {
  if (!token || !dash || document.hidden) return;
  beats += 1;
  if (board) return refresh();
  // The other sections are lists somebody may be reading or typing in; they are drawn again less often.
  const busy = $("sheet").open || $("big").open || ["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement?.tagName);
  if (beats % 4 === 0 && !busy) load();
}, 5000);
setInterval(() => {
  if ($("live").hidden) return;
  const seconds = Math.round((Date.now() - lastHeard) / 1000);
  $("live").classList.toggle("down", !heard);
  $("live-text").textContent = !heard ? "Reconnecting…" : seconds < 3 ? "Live · updated just now" : `Live · updated ${seconds} s ago`;
}, 1000);

// ---------------------------------------------------------------- the overview, live

function tilesFor(role) {
  const make = (options, apply) => {
    const tile = statTile(options);
    return { node: tile.node, apply: (d, more) => apply(tile, d.tiles, d, more) };
  };
  const fortnight = (d, k) => d.trend.map((one) => one[k]);
  const all = {
    companies: make({ label: "Companies", go: () => go("companies") }, (tile, t, d, more) => tile.set(more.companies.length)),
    online: make({ label: "Online now", go: role === "inspector" || role === "technical" ? null : () => go("people") }, (tile, t) => tile.set(t.online, { note: "of " + count(t.people, "person", "people") })),
    reports: make({ label: role === "inspector" ? "My reports" : "Reports", go: () => go("reports") }, (tile, t, d) => tile.set(t.reports, { today: t.reports_today, yesterday: t.reports_yesterday, series: fortnight(d, "reports") })),
    unsettled: make({ label: "Issues not closed", go: () => go("issues", "unsettled") }, (tile, t, d) => tile.set(t.unsettled, { today: t.issues_today, yesterday: t.issues_yesterday, series: fortnight(d, "reported") })),
    serious: make({ label: "High or critical", go: () => go("issues", "serious") }, (tile, t) => tile.set(t.serious, { tone: t.serious ? "alert" : "", note: "among those not closed" })),
    overdue: make({ label: "Overdue", go: () => go("issues", "overdue") }, (tile, t) => tile.set(t.overdue, { tone: t.overdue ? "alert" : "", note: "past their day" })),
    toGive: make({ label: "To give out", go: () => go("issues", "open") }, (tile, t) => tile.set(t.open, { tone: t.open ? "act" : "", note: "waiting for a technician" })),
    withTech: make({ label: "With technicians", go: () => go("issues", "assigned") }, (tile, t) => tile.set(t.assigned, { note: "being worked on" })),
    toCheck: make({ label: "Fixed, to check", go: () => go("issues", "fixed") }, (tile, t) => tile.set(t.fixed, { tone: t.fixed ? "act" : "", note: "waiting for your check" })),
    closed: make({ label: "Closed", go: () => go("issues", "closed"), upIsGood: true }, (tile, t, d) => tile.set(t.closed, { today: t.closed_today, yesterday: t.closed_yesterday, series: fortnight(d, "closed"), tone: "good" })),
    reported: make({ label: "Issues I reported", go: () => go("issues", "all") }, (tile, t, d) => tile.set(t.issues, { today: t.issues_today, yesterday: t.issues_yesterday, series: fortnight(d, "reported") })),
    todo: make({ label: "To do", go: () => go("issues", "todo") }, (tile, t) => tile.set(t.my_jobs, { tone: t.my_jobs ? "act" : "", note: "given to you" })),
    late: make({ label: "Overdue", go: () => go("issues", "todo") }, (tile, t) => tile.set(t.my_overdue, { tone: t.my_overdue ? "alert" : "", note: "past their day" })),
    waitingCheck: make({ label: "Fixed, waiting for the check", go: () => go("issues", "fixed") }, (tile, t) => tile.set(t.my_fixed, { note: "with your manager" })),
    done: make({ label: "Done", go: () => go("issues", "closed") }, (tile, t) => tile.set(t.my_closed, { tone: "good", note: "checked and closed" })),
  };
  const pick = {
    admin: ["companies", "online", "reports", "unsettled", "overdue", "closed"],
    md: ["reports", "unsettled", "serious", "overdue", "toCheck", "closed"],
    manager: ["toGive", "withTech", "toCheck", "overdue", "reports", "closed"],
    inspector: ["reports", "reported", "unsettled", "closed"],
    technical: ["todo", "late", "waitingCheck", "done"],
  };
  return pick[role].map((name) => all[name]);
}

function overviewBoard() {
  const me = dash.me;
  const role = me.role;
  const leads = role === "admin" || role === "md" || role === "manager";
  const hour = new Date().getHours();
  heading(
    role === "admin" ? "Control room" : (hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening") + ", " + me.name.split(" ")[0],
    [me.role_name, me.organisation, me.reports_to ? "reporting to " + me.reports_to : ""].filter(Boolean).join(" · "));

  const tiles = tilesFor(role);
  const trend = lineChart({
    name: "Issues reported and closed on each of the last 14 days",
    tall: true,
    series: [{ key: "reported", label: "Reported", colour: "var(--series-1)", wash: true }, { key: "closed", label: "Closed", colour: "var(--series-2)" }],
    label: (row) => Number(row.day.slice(8)),
    title: (row) => day(row.day),
  });
  const stages = stageBar();
  const severity = barList({ colour: (row) => SEVERITY_COLOUR[row.name], word: ", not closed" });
  const sites = barList({ stacked: true });
  const kinds = barList({ stacked: true });
  const lately = feed();
  const online = onlineList();
  const health = role === "admin" ? healthList() : null;
  const firms = role === "admin" ? liveTable([
    { title: "Company", value: (row) => row.name },
    { title: "Managing director", value: (row) => [row.managing_director, row.managing_director_id].filter(Boolean).join(" · ") },
    { title: "People", num: true, value: (row) => row.people },
    { title: "Reports", num: true, value: (row) => row.reports },
    { title: "Issues", num: true, value: (row) => row.issues },
    { title: "Not closed", num: true, value: (row) => row.open_issues },
    { title: "Closed so far", value: closedMeter },
  ]) : null;
  const teams = liveTable([
    { title: "Led by", value: (row) => row.lead },
    { title: "Reports", num: true, value: (row) => row.reports },
    { title: "Issues", num: true, value: (row) => row.issues },
    { title: "Not closed", num: true, value: (row) => row.open },
    { title: "Overdue", num: true, value: (row) => row.overdue, late: (row) => row.overdue > 0 },
    { title: "Closed so far", value: (row) => closedMeter({ issues: row.issues, open_issues: row.open }) },
  ]);

  const code = me.invite_code ? el("div", { class: "card code" },
    el("div", {}, el("div", { class: "hint", text: role === "md" ? "COMPANY CODE" : "TEAM CODE" }), el("div", { class: "digits", text: me.invite_code })),
    el("p", { text: `Give this code to ${role === "md" ? "your managers (and any inspector or technician who reports straight to you)" : "your inspectors and technicians"}. They type it when they sign up in the app, and their sign-up then comes to you to approve.` }),
    el("button", { onclick: () => navigator.clipboard?.writeText(me.invite_code).then(() => note("Code copied."), () => note("Copy it by hand: " + me.invite_code)) ?? note("Copy it by hand: " + me.invite_code) }, "Copy")) : null;
  const waiting = el("div", { class: "card callout", hidden: true }, el("p"), el("button", { class: "primary", text: "Review", onclick: () => go("approvals") }));
  const quiet = el("div", { class: "card hint", hidden: true, text:
    role === "technical" ? "Nothing has been given to you yet. When your manager gives you an issue, it appears here."
      : role === "inspector" ? "No report yet. Build a report in the DeskLess app; it is sent here as soon as the phone has a network."
      : "No report has arrived yet. Reports are sent from the DeskLess app as soon as the phone has a network. This page fills in by itself when the first one comes." });
  const teamsCard = el("div", { class: "card panel", hidden: true }, el("div", { class: "head" }, el("h4", { text: "Teams, side by side" })), teams.node);

  const panel = (title, body, width) => el("div", { class: "card panel " + width }, el("div", { class: "head" }, el("h4", { text: title })), body);
  const closedKey = el("div", { class: "legend" }, key("square", "var(--series-1)", "Not closed"), key("square", "var(--rest)", "Closed"));
  const parts = [
    code, waiting, quiet,
    el("div", { class: "tiles" }, ...tiles.map((tile) => tile.node)),
    el("div", { class: "cols" },
      chartCard("Reported and closed, last 14 days", trend, el("div", { class: "legend" }, key("line", "var(--series-1)", "Reported"), key("line", "var(--series-2)", "Closed")), "w8"),
      stages.node),
    el("div", { class: "cols" },
      panel("Happening now", lately.node, leads ? "w4" : "w6"),
      leads ? panel("Online now", online.node, "w4") : null,
      health ? panel("Server health", health.node, "w4") : panel("Not closed, by severity", severity.node, leads ? "w4" : "w6")),
    el("div", { class: "cols" },
      health ? panel("Not closed, by severity", severity.node, "w4") : null,
      el("div", { class: "card panel " + (health ? "w4" : "w6") }, el("div", { class: "head" }, el("h4", { text: "By site" }), closedKey.cloneNode(true)), sites.node),
      el("div", { class: "card panel " + (health ? "w4" : "w6") }, el("div", { class: "head" }, el("h4", { text: "By type" }), closedKey), kinds.node)),
    firms ? panel("Companies", firms.node, "") : null,
    teamsCard,
  ].filter(Boolean);
  parts.forEach((part, at) => part.style.setProperty("--i", at));

  return {
    node: el("div", { class: "board" }, ...parts),
    update(d, more) {
      const t = d.tiles;
      for (const tile of tiles) tile.apply(d, more);
      trend.update(d.trend);
      stages.update(t);
      severity.update(Object.entries(d.by_severity).map(([name, n]) => ({ name: SEVERITY[name], open: n, total: n })));
      sites.update(d.by_site);
      kinds.update(d.by_type);
      lately.update(d.activity);
      online.update(d.online);
      if (health) health.update(more.system);
      if (firms) firms.update(more.companies);
      teams.update(d.teams);
      teamsCard.hidden = role !== "md" || !d.teams.length;
      waiting.hidden = !t.waiting_people;
      waiting.querySelector("p").textContent = `${count(t.waiting_people, "sign-up is", "sign-ups are")} waiting for you to approve.`;
      quiet.hidden = t.issues > 0 || t.reports > 0;
    },
  };
}

// ---------------------------------------------------------------- the server itself, for the administrator

function systemBoard() {
  heading("System", "The server itself: is it up, how busy, how full");
  const health = healthList();
  const minutes = (sys) => sys.requests.per_minute.map((n, at) => {
    const moment = new Date(Date.now() - (sys.requests.per_minute.length - 1 - at) * 60000);
    return { at: moment.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }), requests: n, ms: sys.requests.average_ms[at] };
  });
  const asked = lineChart({ name: "Requests answered in each of the last 60 minutes", series: [{ key: "requests", label: "Requests", colour: "var(--series-1)", wash: true }], label: (row) => row.at, title: (row) => row.at });
  const speed = lineChart({ name: "Typical answer time in each of the last 60 minutes, in milliseconds", series: [{ key: "ms", label: "Milliseconds", colour: "var(--series-1)" }], label: (row) => row.at, title: (row) => row.at });
  const files = barList({ show: weight, wide: true });
  const rows = liveTable([{ title: "Kept in the database", value: (row) => row.name }, { title: "Rows", num: true, value: (row) => row.n.toLocaleString() }]);
  const panel = (title, body, width) => el("div", { class: "card panel " + width }, el("div", { class: "head" }, el("h4", { text: title })), body);
  const parts = [
    el("div", { class: "cols" }, panel("Server health", health.node, "w6"), panel("Files kept", files.node, "w6")),
    el("div", { class: "cols" }, chartCard("Requests each minute, last hour", asked, null, "w6"), chartCard("Answer time each minute, in milliseconds", speed, null, "w6")),
    panel("What is stored", rows.node, ""),
  ];
  parts.forEach((part, at) => part.style.setProperty("--i", at));
  return {
    node: el("div", { class: "board" }, ...parts),
    update(d, more) {
      const sys = more.system;
      health.update(sys);
      asked.update(minutes(sys));
      speed.update(minutes(sys));
      files.update([["Reports (PDF)", sys.storage.reports], ["Issue pictures", sys.storage.pictures], ["Profile photos", sys.storage.photos]].map(([name, n]) => ({ name, open: n, total: n })));
      rows.update([["Accounts", sys.rows.accounts], ["Companies", sys.rows.companies], ["Reports", sys.rows.reports], ["Issues", sys.rows.issues], ["Events in the history", sys.rows.events]].map(([name, n]) => ({ name, n })));
    },
  };
}

// ---------------------------------------------------------------- people: approvals, the list, companies

function facts(pairs) {
  return el("dl", {}, ...pairs.filter(([, value]) => value).flatMap(([label, value]) => [el("dt", { text: label }), el("dd", { text: value })]));
}

function person(row, actions) {
  return el("div", { class: "item" },
    picture(`/people/${row.id}/photo`, "Photo of " + row.name, true),
    el("div", {},
      el("h4", {}, row.name + " ", chip(row.role_name, "role"), row.me ? chip("You") : null, row.status === "suspended" ? chip("Suspended", "bad") : null),
      row.unique_id ? el("div", { class: "id", text: row.unique_id }) : null,
      facts([
        ["Email", row.email], ["Mobile", row.phone], ["Company", row.organisation], ["Reports to", row.reports_to],
        ["Aadhaar", row.aadhaar], ["Code to join under", row.status === "approved" ? row.invite_code : ""],
        [row.status === "pending_approval" ? "Email confirmed" : row.status === "rejected" ? "Rejected" : "Approved", when(row.decided_at || row.email_verified_at)],
        ["Last seen", row.last_seen_at ? ago(row.last_seen_at) : ""],
      ]),
      row.status === "rejected" && row.reject_reason ? el("div", { class: "reason", text: "Reason: " + row.reject_reason }) : null),
    actions);
}

function approvals(waiting) {
  heading("Approvals", dash.me.role === "admin" ? "Managing directors bringing a new company wait for you. You may also decide any other sign-up."
    : dash.me.role === "md" ? "The people who signed up with your company code, and anyone else waiting in your company."
    : "The people who signed up with your team code.");
  const cards = waiting.map((row) => {
    const yes = el("button", { class: "primary", text: "Approve" });
    yes.onclick = async () => {
      yes.disabled = true;
      try {
        const done = await post(`/people/${row.id}/approve`);
        note(`${done.name} is approved. DeskLess ID: ${done.unique_id}. ` + (done.emailed ? "It has been emailed to them, and their app shows it." : "The email could not be sent; their app shows them the ID."));
      } catch (error) { note(error.message, true); }
      load();
    };
    const no = el("button", { class: "danger", text: "Reject", onclick: () => rejectSheet(row) });
    return person(row, el("div", { class: "actions" }, yes, no));
  });
  return [cards.length ? el("div", { class: "list" }, ...cards) : el("div", { class: "empty", text: "Nobody is waiting for you." })];
}

function rejectSheet(row) {
  const reason = el("textarea", { rows: 3, placeholder: "For example: the photo does not show a face." });
  const sure = el("button", { class: "danger", text: "Reject" });
  sure.onclick = async () => {
    sure.disabled = true;
    try {
      await post(`/people/${row.id}/reject`, { reason: reason.value });
      note(`${row.name}'s sign-up is rejected. They have been told why.`);
    } catch (error) { note(error.message, true); }
    $("sheet").close();
    load();
  };
  sheet(el("div", { class: "top" }, el("h3", { text: "Reject this sign-up?" })),
    el("p", { class: "hint", text: `${row.name} · ${row.role_name} · ${row.email}` }),
    el("label", { text: "Reason (emailed to the person)" }), reason,
    el("div", { class: "row end" }, el("button", { text: "Cancel", onclick: () => $("sheet").close() }), sure));
}

function suspendSheet(row) {
  const sure = el("button", { class: "danger", text: "Suspend" });
  sure.onclick = async () => {
    sure.disabled = true;
    try {
      await post(`/people/${row.id}/suspend`);
      note(`${row.name} is suspended and can no longer sign in.`);
    } catch (error) { note(error.message, true); }
    $("sheet").close();
    load();
  };
  sheet(el("div", { class: "top" }, el("h3", { text: "Suspend this account?" })),
    el("p", { class: "hint", text: `${row.name} · ${row.role_name} · ${row.unique_id}` }),
    el("p", { text: "They are signed out everywhere at once and cannot sign in until you let them back in. Their reports and issues stay as they are." }),
    el("div", { class: "row end" }, el("button", { text: "Cancel", onclick: () => $("sheet").close() }), sure));
}

function people(rows) {
  heading(dash.me.role === "manager" ? "My team" : "People", count(rows.length, "person", "people") + (peopleStage === "approved" ? "" : ", " + peopleStage));
  // Those who lead come first, then each kind together, by name.
  const order = ["md", "manager", "inspector", "technical"];
  rows.sort((a, b) => (order.indexOf(a.role) - order.indexOf(b.role)) || a.name.localeCompare(b.name));
  const list = el("div", { class: "list" });
  const shown = () => {
    const words = peopleSearch.trim().toLowerCase();
    return rows.filter((row) => (!peopleRole || row.role === peopleRole)
      && (!words || [row.name, row.email, row.unique_id, row.organisation, row.phone, row.reports_to].join(" ").toLowerCase().includes(words)));
  };
  const draw = () => {
    const cards = shown().map((row) => {
      const hands = [
        row.role === "technical" ? count(row.open_work, "job") + " in hand" : "",
        row.reports ? count(row.reports, "report") + " sent" : "",
        row.team ? "leads " + count(row.team, "person", "people") : "",
      ].filter(Boolean);
      const back = el("button", { class: "primary", text: "Let back in" });
      back.onclick = async () => {
        back.disabled = true;
        try { await post(`/people/${row.id}/reactivate`); note(`${row.name} can sign in again.`); } catch (error) { note(error.message, true); }
        load();
      };
      return person(row, el("div", { class: "side" }, ...hands.map((text) => el("span", { text })),
        row.may_decide && row.status === "approved" ? el("button", { class: "small danger", text: "Suspend", onclick: () => suspendSheet(row) }) : null,
        row.may_decide && row.status === "suspended" ? back : null));
    });
    list.replaceChildren(...(cards.length ? cards : [el("div", { class: "empty", text: "Nobody here." })]));
  };
  const search = el("input", { class: "search", type: "search", placeholder: "Search by name, email or ID", value: peopleSearch });
  search.oninput = () => { peopleSearch = search.value; draw(); };
  const roles = [["", "Everyone"], ["md", "Managing directors"], ["manager", "Managers"], ["inspector", "Inspectors"], ["technical", "Technical"]];
  const kinds = el("div", { class: "pills" });
  const drawKinds = () => kinds.replaceChildren(...roles.filter(([role]) => !role || rows.some((row) => row.role === role)).map(([role, label]) =>
    el("button", { class: role === peopleRole ? "on" : "", onclick: () => { peopleRole = role; drawKinds(); draw(); } }, label, el("span", { class: "n", text: role ? rows.filter((row) => row.role === role).length : rows.length }))));
  drawKinds();
  draw();
  return [
    el("div", { class: "tools" },
      el("div", { class: "pills", style: "margin:0" }, ...[["approved", "Approved"], ["suspended", "Suspended"], ["rejected", "Rejected"]].map(([stage, label]) =>
        el("button", { class: stage === peopleStage ? "on" : "", text: label, onclick: () => { peopleStage = stage; load(); } }))),
      search,
      el("button", { text: "Export", onclick: () => download("deskless-people.csv", [
        ["Name", "name"], ["Role", "role_name"], ["DeskLess ID", "unique_id"], ["Email", "email"], ["Mobile", "phone"], ["Company", "organisation"],
        ["Reports to", "reports_to"], ["Status", "status"], ["Reports sent", "reports"], ["Jobs in hand", "open_work"], ["Last seen", (row) => when(row.last_seen_at)],
      ], shown()) })),
    kinds, list,
  ];
}

function companies(rows) {
  heading("Companies", count(rows.length, "company", "companies"));
  if (!rows.length) return [el("div", { class: "empty", text: "No company yet. A company appears when you approve its managing director." })];
  const table = liveTable([
    { title: "Company", value: (row) => row.name },
    { title: "Managing director", value: (row) => [row.managing_director, row.managing_director_id].filter(Boolean).join(" · ") },
    { title: "People", num: true, value: (row) => row.people },
    { title: "Reports", num: true, value: (row) => row.reports },
    { title: "Issues", num: true, value: (row) => row.issues },
    { title: "Not closed", num: true, value: (row) => row.open_issues },
    { title: "Closed so far", value: closedMeter },
    { title: "Since", value: (row) => when(row.created_at) },
  ]);
  table.update(rows);
  return [
    el("div", { class: "tools" }, el("button", { text: "Export", onclick: () => download("deskless-companies.csv", [
      ["Company", "name"], ["Managing director", "managing_director"], ["DeskLess ID", "managing_director_id"], ["People", "people"], ["Reports", "reports"],
      ["Issues", "issues"], ["Not closed", "open_issues"], ["Since", (row) => when(row.created_at)],
    ], rows) })),
    el("div", { class: "card" }, table.node),
  ];
}

// ---------------------------------------------------------------- reports

async function openPdf(report) {
  // The tab is opened at once, while the click still counts, and pointed at the PDF when it has arrived.
  const tab = window.open("", "_blank");
  try {
    const blob = await api(`/reports/${report.id}/pdf`).then((response) => response.blob());
    const url = URL.createObjectURL(blob);
    if (tab) tab.location = url;
    else el("a", { href: url, download: report.pdf_name }).click();
  } catch (error) {
    if (tab) tab.close();
    note(error.message, true);
  }
}

function reports(rows) {
  heading(dash.me.role === "inspector" ? "My reports" : "Reports", count(rows.length, "report"));
  if (!rows.length) return [el("div", { class: "empty", text: "No report yet. The DeskLess app sends each report it builds as soon as the phone has a network." })];
  const list = el("div", { class: "list" });
  const shown = () => {
    const words = reportSearch.trim().toLowerCase();
    return rows.filter((row) => !words || [row.place, row.title, row.sent_by, row.person, row.verdict].join(" ").toLowerCase().includes(words));
  };
  const draw = () => {
    const cards = shown().map((row) => {
      const verdict = VERDICT[row.verdict];
      return el("div", { class: "item" },
        el("div", { class: "blank", style: "display:grid;place-items:center;font-weight:800;color:var(--mute)", text: "PDF" }),
        el("div", {},
          el("h4", { text: row.place || row.title }),
          el("div", { class: "meta", text: [row.title, "by " + (row.sent_by || row.person), when(row.built_at)].join(" · ") }),
          el("div", { class: "tags" },
            chip(count(row.issues, "issue")),
            row.open_issues ? chip(row.open_issues + " not closed", "warn") : row.issues ? chip("All closed", "good") : null,
            verdict ? el("span", { class: "chip " + verdict[0], text: verdict[1], title: row.verdict_reason }) : null,
            row.signed ? chip("Signed with fingerprint", "good") : null),
          verdict && row.verdict !== "GENUINE" && row.verdict_reason ? el("div", { class: "meta", style: "margin-top:8px", text: row.verdict_reason }) : null),
        el("div", { class: "actions" },
          el("button", { class: "primary", text: "Open PDF", onclick: () => openPdf(row) }),
          el("button", { text: "See issues", onclick: () => { ofReport = row; issueFilter = "all"; view = "issues"; load(); } })));
    });
    list.replaceChildren(...(cards.length ? cards : [el("div", { class: "empty", text: "Nothing matches." })]));
  };
  const search = el("input", { class: "search", type: "search", placeholder: "Search by site, person or verdict", value: reportSearch });
  search.oninput = () => { reportSearch = search.value; draw(); };
  draw();
  return [
    el("div", { class: "tools" }, search, el("button", { text: "Export", onclick: () => download("deskless-reports.csv", [
      ["Site", "place"], ["Report", "title"], ["Sent by", "sent_by"], ["Built", (row) => when(row.built_at)], ["Issues", "issues"], ["Not closed", "open_issues"],
      ["AI review", "verdict"], ["Why", "verdict_reason"], ["Signed", (row) => (row.signed ? "yes" : "no")],
    ], shown()) })),
    list,
  ];
}

// ---------------------------------------------------------------- issues

function filters() {
  const mine = (issue) => issue.assigned_to === dash.me.id;
  if (dash.me.role === "technical") return [
    ["todo", "To do", (issue) => mine(issue) && issue.status === "assigned"],
    ["fixed", "Waiting for the check", (issue) => mine(issue) && issue.status === "fixed"],
    ["closed", "Done", (issue) => mine(issue) && issue.status === "closed"],
  ];
  return [
    ["unsettled", "Not closed", (issue) => issue.status !== "closed"],
    ["open", dash.may_direct ? "To give out" : "Not given out yet", (issue) => issue.status === "open"],
    ["assigned", "With technicians", (issue) => issue.status === "assigned"],
    ["fixed", "Fixed, to check", (issue) => issue.status === "fixed"],
    ["overdue", "Overdue", (issue) => issue.overdue],
    ["serious", "High or critical", (issue) => issue.status !== "closed" && (issue.severity === "HIGH" || issue.severity === "CRITICAL")],
    ["closed", "Closed", (issue) => issue.status === "closed"],
    ["all", "All", () => true],
  ];
}

function issueCard(issue) {
  const due = issue.due_on ? day(issue.due_on) : issue.due_text;
  return el("button", { class: "item", onclick: () => openIssue(issue.id) },
    issue.has_photo ? picture(`/issues/${issue.id}/photo`, issue.title) : el("div", { class: "blank" }),
    el("div", {},
      el("h4", { text: issue.title }),
      el("div", { class: "meta", text: [issue.site, issue.location].filter(Boolean).join(" · ") }),
      el("div", { class: "tags" },
        chip(SEVERITY[issue.severity] || issue.severity, issue.severity), statusChip(issue),
        issue.category ? chip(issue.category) : null, issue.overdue ? chip("Overdue", "overdue") : null)),
    el("div", { class: "side" },
      due ? el("span", { text: "Due " + due }) : null,
      el("span", { text: issue.assigned_to_name ? "With " + issue.assigned_to_name : "From " + issue.reported_by })));
}

function shownIssues() {
  const all = filters();
  const chosen = all.find(([name]) => name === issueFilter) || all[0];
  const words = issueSearch.trim().toLowerCase();
  const shown = issues.filter(chosen[2])
    .filter((issue) => !words || [issue.title, issue.site, issue.location, issue.category, issue.note, issue.assigned_to_name, issue.reported_by].join(" ").toLowerCase().includes(words));
  // In one report the order of the walk is kept; otherwise the most pressing come first.
  if (!ofReport) shown.sort((a, b) => (b.overdue - a.overdue) || (SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity)) || (b.captured_at > a.captured_at ? 1 : -1));
  return shown;
}

function issueList() {
  const shown = shownIssues();
  return shown.length ? el("div", { class: "list", id: "issue-list" }, ...shown.map(issueCard))
    : el("div", { class: "empty", id: "issue-list", text: issues.length ? "Nothing here." : dash.me.role === "technical" ? "Nothing has been given to you yet." : "No issues yet." });
}

function issuesView() {
  const all = filters();
  if (!all.some(([name]) => name === issueFilter)) issueFilter = all[0][0];
  heading(
    ofReport ? (ofReport.place || ofReport.title) : dash.me.role === "technical" ? "My jobs" : dash.me.role === "inspector" ? "My issues" : "Issues",
    ofReport ? `${ofReport.title} · ${when(ofReport.built_at)}` : dash.me.role === "technical" ? "Open a job to see the picture. When it is done, mark it fixed with a photo of the repair."
      : dash.may_direct ? "Give each issue to a technician, then check the repair and close it." : "");
  const search = el("input", { class: "search", type: "search", placeholder: "Search issues", value: issueSearch });
  search.oninput = () => { issueSearch = search.value; $("issue-list").replaceWith(issueList()); };
  return [
    el("div", { class: "pills" }, ...all.map(([name, label, test]) =>
      el("button", { class: name === issueFilter ? "on" : "", onclick: () => { issueFilter = name; load(); } }, label, el("span", { class: "n", text: issues.filter(test).length })))),
    el("div", { class: "tools" }, search,
      ofReport ? el("button", { text: "All issues", onclick: () => go("issues", issueFilter) }) : null,
      el("button", { text: "Export", onclick: () => download("deskless-issues.csv", [
        ["Issue", "title"], ["Site", "site"], ["Where", "location"], ["Type", "category"], ["Severity", (row) => SEVERITY[row.severity] || row.severity],
        ["Stands", (row) => STATUS[row.status] || row.status], ["Overdue", (row) => (row.overdue ? "yes" : "")], ["Due", (row) => row.due_on || row.due_text],
        ["Reported by", "reported_by"], ["Reported", (row) => when(row.captured_at)], ["With", "assigned_to_name"], ["Fixed", (row) => when(row.fixed_at)],
        ["Closed", (row) => when(row.closed_at)], ["Note", "note"],
      ], shownIssues()) })),
    issueList(),
  ];
}

// ---------------------------------------------------------------- one issue, and what can be done with it

function sheet(...content) {
  $("sheet").replaceChildren(el("div", { class: "sheet" }, ...content));
  if (!$("sheet").open) $("sheet").showModal();
}
$("sheet").addEventListener("click", (event) => { if (event.target === $("sheet")) $("sheet").close(); });

// Runs an action from the sheet, then shows the issue as it now stands.
async function act(button, work, said) {
  button.disabled = true;
  try {
    const issue = await work();
    fetched.delete(`/issues/${issue.id}/fix-photo`);
    note(said(issue));
    await openIssue(issue.id);
    load();
  } catch (error) {
    note(error.message, true);
    button.disabled = false;
  }
}

async function giveOutBox(issue) {
  const technicians = await get("/technicians");
  if (!technicians.length) {
    return el("div", { class: "box" }, el("h4", { text: "Give it to a technician" }),
      el("p", { class: "hint", text: "You have no technician yet. Give your code to a technician; once they sign up with it and you approve them, they appear here." }));
  }
  const who = el("select", {}, ...technicians.map((one) =>
    el("option", { value: one.id, selected: one.id === issue.assigned_to, text: `${one.name} · ${count(one.open_work, "job")} in hand` })));
  const soon = new Date(Date.now() + 3 * 86400000);
  const local = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  const due = el("input", { type: "date", value: issue.due_on || local(soon) });
  const give = el("button", { class: "primary", text: issue.assigned_to ? "Give to this person instead" : "Give out" });
  give.onclick = () => act(give, () => post(`/issues/${issue.id}/assign`, { to: who.value, due_on: due.value }), (now) => `Given to ${now.assigned_to_name}. They have been told.`);
  return el("div", { class: "box" }, el("h4", { text: issue.assigned_to ? "Give it to someone else" : "Give it to a technician" }),
    issue.owner ? el("p", { class: "hint", text: `The inspector's note names ${issue.owner}.` }) : null,
    el("div", { class: "row" }, el("div", {}, el("label", { text: "Technician" }), who), el("div", {}, el("label", { text: "Due" }), due), give));
}

function fixedBox(issue) {
  const photo = el("input", { type: "file", accept: "image/*", capture: "environment" });
  const words = el("textarea", { rows: 2, placeholder: "What was done (optional)" });
  const done = el("button", { class: "primary", text: "Mark fixed" });
  done.onclick = () => {
    if (!photo.files.length) return note("Add a photo of the repair first.", true);
    act(done, async () => {
      const form = new FormData();
      form.append("note", words.value);
      form.append("photo", await smaller(photo.files[0]), "repair.jpg");
      return api(`/issues/${issue.id}/fixed`, { method: "POST", body: form }).then((response) => response.json());
    }, () => "Marked fixed. It now waits for the check.");
  };
  return el("div", { class: "box" }, el("h4", { text: "Is it done?" }),
    el("p", { class: "hint", text: "Take a photo that shows the repair. Your manager checks it against the first picture." }),
    el("label", { text: "Photo of the repair" }), photo, el("label", { text: "Note" }), words,
    el("div", { class: "row end" }, done));
}

function checkBox(issue) {
  const words = el("input", { placeholder: "A word on it (optional)" });
  const fixed = issue.status === "fixed";
  const accept = el("button", { class: fixed ? "primary" : "", text: fixed ? "Accept and close" : "Close without a repair" });
  accept.onclick = () => act(accept, () => post(`/issues/${issue.id}/close`, { note: words.value }), () => "Closed.");
  const back = el("button", { class: "danger", text: "Send back" });
  back.onclick = () => act(back, () => post(`/issues/${issue.id}/reopen`, { note: words.value }), (now) => now.assigned_to_name ? `Sent back to ${now.assigned_to_name}.` : "Open again.");
  return el("div", { class: "box" },
    el("h4", { text: fixed ? "Check the repair" : issue.status === "closed" ? "Came back?" : "Nothing to repair?" }),
    fixed ? el("p", { class: "hint", text: "Compare the two pictures above. Accept the repair, or send it back with what is still wrong." }) : null,
    el("div", { class: "row" }, words, issue.status === "closed" ? null : accept, fixed || issue.status === "closed" ? back : null));
}

async function openIssue(id) {
  let issue;
  try {
    issue = await get("/issues/" + id);
  } catch (error) {
    return note(error.message, true);
  }
  const due = issue.due_on ? day(issue.due_on) : issue.due_text;
  const place = issue.lat != null && issue.lon != null
    ? el("a", { href: `https://www.google.com/maps?q=${issue.lat},${issue.lon}`, target: "_blank", rel: "noopener", style: "color:#8fbcf5", text: `${issue.lat.toFixed(5)}, ${issue.lon.toFixed(5)}` })
    : null;
  const details = facts([
    ["Site", issue.site], ["Where", issue.location], ["Type", issue.category],
    ["Reported", `${issue.reported_by} · ${when(issue.captured_at)}`],
    ["Named in the note", issue.owner], ["Due", due],
    ["With", issue.assigned_to_name ? `${issue.assigned_to_name} · given by ${issue.assigned_by}, ${when(issue.assigned_at)}` : ""],
    ["Repair", issue.fixed_at ? [when(issue.fixed_at), issue.fix_note].filter(Boolean).join(" · ") : ""],
    ["Closed", issue.closed_at ? `${issue.closed_by} · ${when(issue.closed_at)}` : ""],
    ["Note", issue.note], ["AI check", [CHECK[issue.check], issue.seen].filter(Boolean).join(". ")], ["Measured", issue.measured],
  ]);
  if (place) details.append(el("dt", { text: "Position" }), el("dd", {}, place));

  const boxes = [];
  if (issue.assigned_to === dash.me.id && issue.status === "assigned") boxes.push(fixedBox(issue));
  if (dash.may_direct) {
    if (issue.status === "open" || issue.status === "assigned") boxes.push(await giveOutBox(issue));
    boxes.push(checkBox(issue));
  }
  sheet(
    el("div", { class: "top" }, el("h3", { text: issue.title }), el("button", { class: "x quiet", text: "✕", "aria-label": "Close", onclick: () => $("sheet").close() })),
    el("div", { class: "tags", style: "display:flex;gap:6px;flex-wrap:wrap" },
      chip(SEVERITY[issue.severity] || issue.severity, issue.severity), statusChip(issue), issue.overdue ? chip("Overdue", "overdue") : null),
    el("div", { class: "pictures" },
      issue.has_photo ? el("figure", {}, picture(`/issues/${issue.id}/photo`, issue.title, true), el("figcaption", { text: "As found" })) : null,
      issue.has_fix_photo ? el("figure", {}, picture(`/issues/${issue.id}/fix-photo`, "The repair", true), el("figcaption", { text: "The repair" })) : null),
    details,
    ...boxes,
    issue.history.length ? el("div", { class: "history" }, el("h3", { class: "section", text: "What has happened" }),
      ...issue.history.map((event) => el("div", { class: "row-h" }, el("time", { text: when(event.at) }), el("span", { text: event.text })))) : null,
  );
}

// ---------------------------------------------------------------- start

openedFromTheApp().then(show);
