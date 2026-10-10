"use strict";

// The live linkage: the server in the middle, the services beside it, each place on a ring around them
// and each person beside their place, drawn in three dimensions on a canvas with nothing fetched from
// elsewhere. Whatever moves on the server (a walk arriving, an issue ranked and routed, passed on, given
// out, fixed, the model asked, a reminder sent, a phone told) is a packet flying from one node to the
// other, within a second of the thing itself: the page waits on the server and is answered the moment
// there is something to show. What each viewer sees is what their dashboard shows them, decided there.

const $ = (id) => document.getElementById(id);
const store = sessionStorage;
// The page may be served under a path on its domain; the API is beside the page, wherever that is.
const BASE = new URL(".", location.href).pathname;
const calm = matchMedia("(prefers-reduced-motion: reduce)").matches;

let token = store.getItem("deskless-token") || "";

// ---------------------------------------------------------------- what is drawn

const ROLE_COLOUR = { md: "#ffd166", manager: "#3987e5", inspector: "#19b4a0", technical: "#ec835a", member: "#c084fc" };
const NODE_COLOUR = { server: "#ffc31f", assistant: "#a78bfa", writer: "#f472b6", rounds: "#2dd4bf", mail: "#4ade80", admin: "#f3f4f6", company: "#c98500" };
const KIND_COLOUR = {
  walk: "#19b4a0", sent_in: "#c084fc", route: "#a78bfa", message: "#3987e5", assign: "#ec835a", fixed: "#4ade80", closed: "#199e70",
  reopened: "#ff7a6e", model: "#f472b6", rounds: "#2dd4bf", push: "#ffc31f", mail: "#4ade80", signin: "#f3f4f6", listen: "#8a919e", ask: "#6b7280",
};
// Questions and listening are the background hum: drawn faintly, listed only when asked for.
const QUIET = new Set(["ask", "listen"]);
// Each kind in two or three words, for the legend; the server's longer name is the title.
const KIND_SHORT = {
  walk: "walk or report", sent_in: "sent in by a user", route: "assistant routes it", message: "passed on", assign: "given out", fixed: "fixed, with a photo",
  closed: "closed", reopened: "sent back", model: "writing model", rounds: "own rounds", push: "phone told", mail: "email", signin: "signed in", listen: "listening", ask: "a question",
};
const ROLE_ORDER = { md: 0, manager: 1, inspector: 2, technical: 3, member: 4 };
const FLOOR = -46;
const FEED_KEPT = 200;

const nodes = new Map();   // every node by id: the server, a service, a place or a person
let edges = [];            // the standing lines: who answers to whom, who belongs where
const flights = [];        // packets in the air
const ripples = [];        // rings spreading where a packet landed
const feed = [];           // what moved, newest first
let mark = 0;              // the number of the last pulse taken
let lastN = 0;
let me = null;
let services = null;
let kinds = [];
let everyQuestion = false;
let demo = null;           // the demo's timer while it plays
let watching = false;
let selected = null;
const recent = [];         // moments of the real pulses, for the count in the last minute

// ---------------------------------------------------------------- the server

async function api(path, options = {}) {
  const headers = token ? { Authorization: "Bearer " + token } : {};
  let body = options.body;
  if (body) {
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
  return response.json();
}
const get = (path) => api(path);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Builds an element. Text always goes in as text, never as HTML, so nothing anyone typed can run.
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key === "style") node.style.cssText = value;
    else if (key.startsWith("on")) node[key] = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) if (child != null && child !== false && child !== "") node.append(child);
  return node;
}

let noteTimer = 0;
function note(text, bad = false) {
  const box = $("note");
  box.textContent = text;
  box.className = bad ? "bad" : "";
  box.hidden = false;
  clearTimeout(noteTimer);
  noteTimer = setTimeout(() => { box.hidden = true; }, 4000);
}

function clock(iso) {
  return new Date(iso).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}
function ago(iso) {
  if (!iso) return "never";
  const seconds = Math.round((Date.now() - new Date(iso)) / 1000);
  if (seconds < 45) return "just now";
  if (seconds < 3600) return Math.max(1, Math.round(seconds / 60)) + " min ago";
  if (seconds < 86400) return Math.round(seconds / 3600) + " h ago";
  return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}
function lasting(seconds) {
  if (seconds < 90) return seconds + " s";
  if (seconds < 5400) return Math.round(seconds / 60) + " min";
  if (seconds < 129600) return Math.round(seconds / 3600) + " h";
  return Math.round(seconds / 86400) + " days";
}

// ---------------------------------------------------------------- colours

function rgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function mix(hex, towards, amount) {
  const [r, g, b] = rgb(hex);
  const to = towards === "white" ? 255 : 0;
  return `rgb(${Math.round(r + (to - r) * amount)}, ${Math.round(g + (to - g) * amount)}, ${Math.round(b + (to - b) * amount)})`;
}
function withAlpha(hex, alpha) {
  const [r, g, b] = rgb(hex);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

// ---------------------------------------------------------------- the nodes and where they stand

function keep(id, about) {
  const was = nodes.get(id);
  const node = Object.assign(was || { id, lit: 0, pos: { x: 0, y: 0, z: 0 }, at: null }, about);
  nodes.set(id, node);
  return node;
}

function layout(data) {
  const seen = new Set();
  const place = (id, about) => { seen.add(id); return keep(id, about); };
  const s = data.services;
  place("server", { kind: "server", name: "DeskLess server", sub: s.server.under === "/" ? "at the root" : "under " + s.server.under, colour: NODE_COLOUR.server, r: 30, pos: { x: 0, y: 0, z: 0 }, about: s.server });
  const ring = [
    ["assistant", "The assistant", "rules: rank, route, next step", s.assistant],
    ["writer", "Writing model", s.writer.on ? s.writer.model || "local model" : s.writer.set ? "set, not answering" : "none running", s.writer],
    ["rounds", "Own rounds", s.rounds.on ? "every " + lasting(s.rounds.every_seconds) : "switched off", s.rounds],
    ["mail", "Mail", s.mail.set ? "mail server set" : "no mail server", s.mail],
    ["admin", "Administrator", "web console", {}],
  ];
  ring.forEach(([id, name, sub, about], i) => {
    const a = -Math.PI / 2 + (i * 2 * Math.PI) / ring.length;
    place(id, { kind: "service", name, sub, colour: NODE_COLOUR[id], r: 16, pos: { x: 125 * Math.cos(a), y: 62, z: 125 * Math.sin(a) }, about, off: (id === "writer" && !s.writer.on) || (id === "rounds" && !s.rounds.on) || (id === "mail" && !s.mail.set) });
  });

  const firms = data.companies;
  const R = Math.max(300, 62 * firms.length);
  const angle = new Map();
  firms.forEach((firm, i) => {
    const a = Math.PI / 2 + (i * 2 * Math.PI) / Math.max(1, firms.length);
    angle.set(firm.id, a);
    place("c:" + firm.id, {
      kind: "company", name: firm.name, sub: [firm.kind_name, firm.open_door ? "open to its users" : ""].filter(Boolean).join(" · ") || "a place",
      colour: NODE_COLOUR.company, r: 19, pos: { x: R * Math.cos(a), y: 0, z: R * Math.sin(a) }, about: firm,
    });
  });

  const byFirm = new Map();
  const loose = [];
  const members = [];
  for (const person of data.people) {
    if (person.role === "member") members.push(person);
    else if (person.company_id && angle.has(person.company_id)) (byFirm.get(person.company_id) || byFirm.set(person.company_id, []).get(person.company_id)).push(person);
    else loose.push(person);
  }
  const describe = (person, pos) => place("p:" + person.id, {
    kind: "person", name: person.name, sub: [person.role_name, person.unique_id].filter(Boolean).join(" · "), colour: ROLE_COLOUR[person.role] || "#b3bac6",
    r: person.role === "md" ? 14 : 12, pos, about: person, online: person.online, device: person.device, role: person.role,
  });
  for (const [firmId, people] of byFirm) {
    people.sort((a, b) => (ROLE_ORDER[a.role] ?? 9) - (ROLE_ORDER[b.role] ?? 9) || a.name.localeCompare(b.name));
    const a = angle.get(firmId);
    const centre = nodes.get("c:" + firmId).pos;
    const k = people.length;
    const r = 72 + 8 * Math.min(k, 10);
    // The managing director stands between the place and the server; the others fan out beyond it.
    const rest = people.filter((person) => person.role !== "md");
    people.filter((person) => person.role === "md").forEach((person, j) => {
      const ang = a + Math.PI + (j - 0.5) * 0.5 * (j ? 1 : 0);
      describe(person, { x: centre.x + 0.8 * r * Math.cos(ang), y: 26, z: centre.z + 0.8 * r * Math.sin(ang) });
    });
    const spread = Math.min(0.75, 1.9 / Math.max(1, rest.length));
    rest.forEach((person, j) => {
      const ang = a + (j - (rest.length - 1) / 2) * spread;
      describe(person, { x: centre.x + r * Math.cos(ang), y: person.role === "manager" ? 14 : 0, z: centre.z + r * Math.sin(ang) });
    });
  }
  loose.forEach((person, j) => {
    const a = Math.PI / 2 + ((j + 0.5) * 2 * Math.PI) / Math.max(1, loose.length);
    describe(person, { x: (R + 90) * Math.cos(a), y: 0, z: (R + 90) * Math.sin(a) });
  });
  // The users of a place stand beyond the place they last wrote to; those who wrote to none, on the outer ring.
  const near = new Map();
  const far = [];
  for (const person of members) {
    if (person.near && angle.has(person.near)) (near.get(person.near) || near.set(person.near, []).get(person.near)).push(person);
    else far.push(person);
  }
  for (const [firmId, people] of near) {
    const a = angle.get(firmId);
    const centre = nodes.get("c:" + firmId).pos;
    const spread = Math.min(0.5, 1.4 / people.length);
    people.forEach((person, j) => {
      const ang = a + (j - (people.length - 1) / 2) * spread;
      describe(person, { x: centre.x + 185 * Math.cos(ang), y: -10, z: centre.z + 185 * Math.sin(ang) });
    });
  }
  far.forEach((person, j) => {
    const a = Math.PI / 2 + 0.4 + ((j + 0.5) * 2 * Math.PI) / Math.max(1, far.length);
    describe(person, { x: (R + 200) * Math.cos(a), y: -10, z: (R + 200) * Math.sin(a) });
  });
  for (const id of [...nodes.keys()]) if (!seen.has(id)) nodes.delete(id);

  // The standing lines.
  edges = [];
  const line = (a, b, kind = "") => { if (nodes.has(a) && nodes.has(b) && a !== b) edges.push({ a, b, kind }); };
  for (const id of ["assistant", "writer", "rounds", "mail", "admin"]) line(id, "server");
  for (const firm of firms) line("c:" + firm.id, "server");
  for (const person of data.people) {
    const id = "p:" + person.id;
    if (person.role === "member") line(id, "c:" + person.near, "faint");
    else if (person.reports_to && nodes.has("p:" + person.reports_to)) line(id, "p:" + person.reports_to);
    else if (person.company_id) line(id, "c:" + person.company_id);
  }
  rings = [125, R, R + 185];
  fit();
  $("n-nodes").textContent = nodes.size;
  $("n-online").textContent = data.people.filter((person) => person.online).length;
}

let rings = [125, 300, 485];

// ---------------------------------------------------------------- the camera and the canvas

const canvas = $("scene");
const ctx = canvas.getContext("2d");
let W = 0, H = 0, DPR = 1;
const camera = { yaw: 0.6, pitch: 0.52, dist: 960, lookY: 8, turning: !calm, touched: 0 };
const focalFor = () => 0.92 * Math.min(W, H);

function resize() {
  DPR = Math.min(2, window.devicePixelRatio || 1);
  W = canvas.clientWidth;
  H = canvas.clientHeight;
  canvas.width = Math.round(W * DPR);
  canvas.height = Math.round(H * DPR);
  ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  fit();
}

// Stands back far enough for the outer ring to fit what the feed leaves free, until the viewer zooms.
function fit() {
  if (camera.zoomed || !W) return;
  const free = W > 760 && feedShown() ? W - 352 : W;
  const tall = W <= 760 && feedShown() ? H * 0.45 : H;
  const outer = rings[rings.length - 1] + 60;
  camera.dist = Math.max(600, Math.min(2600, (focalFor() * outer) / (0.46 * Math.min(free, tall * 1.5))));
}

function project(p) {
  const cy = Math.cos(camera.yaw), sy = Math.sin(camera.yaw);
  const x = p.x * cy - p.z * sy;
  const z = p.x * sy + p.z * cy;
  const y = p.y - camera.lookY;
  const cp = Math.cos(camera.pitch), sp = Math.sin(camera.pitch);
  const y2 = y * cp + z * sp;
  const z2 = -y * sp + z * cp;
  const depth = z2 + camera.dist;
  const s = focalFor() / Math.max(60, depth);
  return { x: centreX() + x * s, y: centreY() - y2 * s, s, depth };
}
// The middle of what is not covered: the feed sits on the right on a wide screen, and on a phone at the
// bottom, under a top bar that wraps to a few rows.
const feedShown = () => !$("feed").classList.contains("off");
const centreX = () => (W > 760 && feedShown() ? (W - 352) / 2 : W / 2);
const centreY = () => (W <= 760 ? (feedShown() ? H * 0.36 : H * 0.52) : H / 2);
// How much the distance dims a thing: near things full, far things in the haze.
const fog = (depth) => Math.max(0.18, Math.min(1, 1.35 - depth / (camera.dist * 1.75)));

function bezier(a, b, t) {
  const dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
  const lift = 36 + 0.22 * Math.sqrt(dx * dx + dz * dz);
  const c = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 + lift, z: (a.z + b.z) / 2 };
  const u = 1 - t;
  return { x: u * u * a.x + 2 * u * t * c.x + t * t * b.x, y: u * u * a.y + 2 * u * t * c.y + t * t * b.y, z: u * u * a.z + 2 * u * t * c.z + t * t * b.z };
}

function drawRing(radius, alpha) {
  ctx.beginPath();
  const steps = 96;
  let dim = 0;
  for (let i = 0; i <= steps; i++) {
    const a = (i * 2 * Math.PI) / steps;
    const p = project({ x: radius * Math.cos(a), y: FLOOR, z: radius * Math.sin(a) });
    dim += fog(p.depth);
    if (i) ctx.lineTo(p.x, p.y); else ctx.moveTo(p.x, p.y);
  }
  ctx.strokeStyle = `rgba(255, 255, 255, ${alpha * (dim / (steps + 1))})`;
  ctx.lineWidth = 1;
  ctx.stroke();
}

function drawFloor() {
  for (const radius of rings) drawRing(radius, 0.13);
  drawRing(rings[rings.length - 1] + 110, 0.06);
  // Spokes, faint, so the turning can be seen.
  ctx.lineWidth = 1;
  for (let i = 0; i < 12; i++) {
    const a = (i * Math.PI) / 6;
    const inner = project({ x: 60 * Math.cos(a), y: FLOOR, z: 60 * Math.sin(a) });
    const outer = project({ x: (rings[2] + 110) * Math.cos(a), y: FLOOR, z: (rings[2] + 110) * Math.sin(a) });
    ctx.strokeStyle = `rgba(255, 255, 255, ${0.045 * fog((inner.depth + outer.depth) / 2)})`;
    ctx.beginPath();
    ctx.moveTo(inner.x, inner.y);
    ctx.lineTo(outer.x, outer.y);
    ctx.stroke();
  }
}

function drawShadow(node, p) {
  const ground = project({ x: node.pos.x, y: FLOOR, z: node.pos.z });
  const rad = node.r * ground.s * 0.9;
  ctx.fillStyle = `rgba(0, 0, 0, ${0.35 * fog(ground.depth)})`;
  ctx.beginPath();
  ctx.ellipse(ground.x, ground.y, rad, rad * Math.max(0.2, Math.sin(camera.pitch)) , 0, 0, 2 * Math.PI);
  ctx.fill();
  // A thin thread from the ground to a raised node, so its height reads.
  if (node.pos.y > FLOOR + 30) {
    ctx.strokeStyle = `rgba(255, 255, 255, ${0.07 * fog(p.depth)})`;
    ctx.beginPath();
    ctx.moveTo(ground.x, ground.y);
    ctx.lineTo(p.x, p.y);
    ctx.stroke();
  }
}

function drawEdges(at) {
  for (const edge of edges) {
    const a = nodes.get(edge.a), b = nodes.get(edge.b);
    const pa = at.get(edge.a), pb = at.get(edge.b);
    const dim = fog((pa.depth + pb.depth) / 2) * (edge.kind === "faint" ? 0.35 : 0.8);
    const glow = Math.max(a.lit, b.lit);
    const gradient = ctx.createLinearGradient(pa.x, pa.y, pb.x, pb.y);
    gradient.addColorStop(0, withAlpha(a.colour, 0.22 * dim + 0.3 * glow));
    gradient.addColorStop(1, withAlpha(b.colour, 0.22 * dim + 0.3 * glow));
    ctx.strokeStyle = gradient;
    ctx.lineWidth = 1 + glow;
    ctx.beginPath();
    ctx.moveTo(pa.x, pa.y);
    ctx.lineTo(pb.x, pb.y);
    ctx.stroke();
  }
}

function drawFlights(now) {
  for (let i = flights.length - 1; i >= 0; i--) {
    const flight = flights[i];
    const t = (now - flight.start) / flight.duration;
    if (t < 0) continue;
    const a = nodes.get(flight.from), b = nodes.get(flight.to);
    if (!a || !b) { flights.splice(i, 1); continue; }
    if (t >= 1) {
      b.lit = 1;
      ripples.push({ id: flight.to, start: now, colour: flight.colour });
      flights.splice(i, 1);
      continue;
    }
    const colour = flight.colour;
    const faint = flight.quiet ? 0.45 : 1;
    const tail = Math.max(0, t - 0.26);
    const steps = 16;
    let last = null;
    for (let k = 0; k <= steps; k++) {
      const u = tail + ((t - tail) * k) / steps;
      const p = project(bezier(a.pos, b.pos, u));
      if (last) {
        const w = k / steps;
        ctx.strokeStyle = withAlpha(colour, faint * w * 0.85 * fog(p.depth));
        ctx.lineWidth = (0.6 + 2.6 * w) * Math.min(1.4, p.s);
        ctx.beginPath();
        ctx.moveTo(last.x, last.y);
        ctx.lineTo(p.x, p.y);
        ctx.stroke();
      }
      last = p;
    }
    // The head: a bright core in a soft glow.
    const head = last;
    const rad = (flight.quiet ? 2.4 : 3.6) * Math.min(1.5, head.s);
    const glow = ctx.createRadialGradient(head.x, head.y, 0, head.x, head.y, rad * 4);
    glow.addColorStop(0, withAlpha(colour, 0.55 * faint));
    glow.addColorStop(1, withAlpha(colour, 0));
    ctx.fillStyle = glow;
    ctx.beginPath();
    ctx.arc(head.x, head.y, rad * 4, 0, 2 * Math.PI);
    ctx.fill();
    ctx.fillStyle = flight.quiet ? colour : "#ffffff";
    ctx.beginPath();
    ctx.arc(head.x, head.y, rad, 0, 2 * Math.PI);
    ctx.fill();
  }
}

function drawRipples(now, at) {
  for (let i = ripples.length - 1; i >= 0; i--) {
    const ripple = ripples[i];
    const t = (now - ripple.start) / 700;
    const p = at.get(ripple.id);
    const node = nodes.get(ripple.id);
    if (t >= 1 || !p || !node) { ripples.splice(i, 1); continue; }
    ctx.strokeStyle = withAlpha(ripple.colour, (1 - t) * 0.8 * fog(p.depth));
    ctx.lineWidth = 2 * (1 - t) + 0.5;
    ctx.beginPath();
    ctx.arc(p.x, p.y, node.r * p.s * (1 + 2.2 * t), 0, 2 * Math.PI);
    ctx.stroke();
  }
}

function glyph(kind, x, y, colour, alpha) {
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.strokeStyle = colour;
  ctx.lineWidth = 1.2;
  if (kind === "phone") {
    ctx.beginPath();
    ctx.roundRect(x - 2.5, y - 4.5, 5, 9, 1.2);
    ctx.stroke();
    ctx.fillStyle = colour;
    ctx.fillRect(x - 1, y + 2.4, 2, 0.8);
  } else {
    ctx.beginPath();
    ctx.rect(x - 5, y - 4, 10, 6.5);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(x - 2.5, y + 4.6);
    ctx.lineTo(x + 2.5, y + 4.6);
    ctx.stroke();
  }
  ctx.restore();
}

function drawNode(node, p) {
  const dim = fog(p.depth);
  const rad = Math.max(3, node.r * p.s);
  const alpha = (node.online === false || node.off ? 0.55 : 1) * dim;
  const lit = node.lit;
  if (lit > 0.02) {
    const halo = ctx.createRadialGradient(p.x, p.y, rad * 0.4, p.x, p.y, rad * (2.2 + lit));
    halo.addColorStop(0, withAlpha(node.colour, 0.55 * lit * dim));
    halo.addColorStop(1, withAlpha(node.colour, 0));
    ctx.fillStyle = halo;
    ctx.beginPath();
    ctx.arc(p.x, p.y, rad * (2.2 + lit), 0, 2 * Math.PI);
    ctx.fill();
  }
  if (node === selected) {
    ctx.strokeStyle = withAlpha("#ffc31f", 0.9);
    ctx.lineWidth = 2;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.arc(p.x, p.y, rad + 6, 0, 2 * Math.PI);
    ctx.stroke();
    ctx.setLineDash([]);
  }
  const body = ctx.createRadialGradient(p.x - rad * 0.38, p.y - rad * 0.42, rad * 0.08, p.x, p.y, rad);
  body.addColorStop(0, mix(node.colour, "white", 0.6));
  body.addColorStop(0.5, node.colour);
  body.addColorStop(1, mix(node.colour, "black", 0.62));
  ctx.globalAlpha = alpha;
  ctx.fillStyle = body;
  ctx.beginPath();
  ctx.arc(p.x, p.y, rad, 0, 2 * Math.PI);
  ctx.fill();
  if (node.kind === "company") {
    // A place is a ringed sphere: the one thing people stand around.
    ctx.strokeStyle = withAlpha(node.colour, 0.9);
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.ellipse(p.x, p.y + rad * 0.15, rad * 1.7, rad * 0.45, -0.25, 0, 2 * Math.PI);
    ctx.stroke();
  }
  ctx.strokeStyle = withAlpha(node.colour, 0.75);
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.arc(p.x, p.y, rad, 0, 2 * Math.PI);
  ctx.stroke();
  ctx.globalAlpha = 1;
  if (node.kind === "person") {
    const dot = Math.max(2.5, rad * 0.24);
    ctx.fillStyle = node.online ? "#3ecf7a" : "#4b5361";
    ctx.strokeStyle = "#0f1115";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(p.x + rad * 0.72, p.y - rad * 0.72, dot, 0, 2 * Math.PI);
    ctx.fill();
    ctx.stroke();
  }
  // The name, and under it the role and ID, when close enough to read.
  const size = Math.max(10, Math.min(15, 13 * p.s));
  if (rad >= 4) {
    ctx.font = `600 ${size}px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`;
    ctx.textAlign = "center";
    ctx.textBaseline = "top";
    ctx.lineWidth = 3;
    ctx.strokeStyle = "rgba(15, 17, 21, .85)";
    ctx.lineJoin = "round";
    let y = p.y + rad + 5;
    ctx.globalAlpha = Math.min(1, dim * 1.3);
    ctx.strokeText(node.name, p.x, y);
    ctx.fillStyle = "#f3f4f6";
    ctx.fillText(node.name, p.x, y);
    if (node.device) glyph(node.device, p.x - ctx.measureText(node.name).width / 2 - 9, y + size * 0.55, node.colour, Math.min(1, dim * 1.3));
    if (node.sub && p.s > 0.62) {
      y += size + 2;
      ctx.font = `${Math.max(9, size - 3)}px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`;
      ctx.strokeText(node.sub, p.x, y);
      ctx.fillStyle = node.kind === "person" ? node.colour : "#b3bac6";
      ctx.fillText(node.sub, p.x, y);
    }
    ctx.globalAlpha = 1;
  }
}

let drawing = false;
let lastFrame = 0;
function frame(now) {
  if (!drawing) return;
  requestAnimationFrame(frame);
  if (canvas.clientWidth !== W || canvas.clientHeight !== H) resize();
  const dt = Math.min(50, now - lastFrame);
  lastFrame = now;
  if (camera.turning && now - camera.touched > 2500) camera.yaw += 0.00012 * dt;

  ctx.clearRect(0, 0, W, H);
  const sky = ctx.createRadialGradient(W / 2, H * 0.45, 20, W / 2, H * 0.45, Math.max(W, H) * 0.8);
  sky.addColorStop(0, "#1a1d24");
  sky.addColorStop(1, "#0b0d11");
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, W, H);

  const at = new Map();
  for (const node of nodes.values()) {
    at.set(node.id, project(node.pos));
    node.lit *= Math.pow(0.996, dt);
    if (node.lit < 0.01) node.lit = 0;
  }
  drawFloor();
  for (const node of nodes.values()) drawShadow(node, at.get(node.id));
  drawEdges(at);
  const order = [...nodes.values()].sort((a, b) => at.get(b.id).depth - at.get(a.id).depth);
  drawRipples(now, at);
  for (const node of order) drawNode(node, at.get(node.id));
  drawFlights(now);
  lastProjection = at;
}
let lastProjection = new Map();

// ---------------------------------------------------------------- turning, zooming, picking

const pointer = { down: false, moved: false, x: 0, y: 0, id: null, pinch: 0 };
canvas.addEventListener("pointerdown", (event) => {
  pointer.down = true;
  pointer.moved = false;
  pointer.x = event.clientX;
  pointer.y = event.clientY;
  pointer.id = event.pointerId;
  canvas.setPointerCapture(event.pointerId);
  canvas.classList.add("dragging");
});
canvas.addEventListener("pointermove", (event) => {
  if (!pointer.down || event.pointerId !== pointer.id) return;
  const dx = event.clientX - pointer.x, dy = event.clientY - pointer.y;
  if (Math.abs(dx) + Math.abs(dy) > 3) pointer.moved = true;
  camera.yaw += dx * 0.005;
  camera.pitch = Math.max(0.12, Math.min(1.25, camera.pitch + dy * 0.004));
  camera.touched = performance.now();
  pointer.x = event.clientX;
  pointer.y = event.clientY;
});
const release = (event) => {
  if (!pointer.down) return;
  pointer.down = false;
  canvas.classList.remove("dragging");
  if (!pointer.moved) pick(event.clientX, event.clientY);
};
canvas.addEventListener("pointerup", release);
canvas.addEventListener("pointercancel", () => { pointer.down = false; canvas.classList.remove("dragging"); });
canvas.addEventListener("wheel", (event) => {
  event.preventDefault();
  camera.dist = Math.max(380, Math.min(2600, camera.dist * Math.pow(1.1, event.deltaY / 100)));
  camera.zoomed = true;
  camera.touched = performance.now();
}, { passive: false });
// Two fingers: the distance between them sets the zoom.
canvas.addEventListener("touchstart", (event) => { if (event.touches.length === 2) pointer.pinch = pinchOf(event); }, { passive: true });
canvas.addEventListener("touchmove", (event) => {
  if (event.touches.length !== 2) return;
  const now = pinchOf(event);
  if (pointer.pinch) camera.dist = Math.max(380, Math.min(2600, camera.dist * pointer.pinch / now));
  pointer.pinch = now;
  camera.zoomed = true;
  camera.touched = performance.now();
}, { passive: true });
const pinchOf = (event) => Math.hypot(event.touches[0].clientX - event.touches[1].clientX, event.touches[0].clientY - event.touches[1].clientY);

function pick(x, y) {
  const box = canvas.getBoundingClientRect();
  const px = x - box.left, py = y - box.top;
  let best = null, nearest = 24;
  for (const node of nodes.values()) {
    const p = lastProjection.get(node.id);
    if (!p) continue;
    const d = Math.hypot(p.x - px, p.y - py) - Math.max(3, node.r * p.s);
    if (d < nearest) { nearest = d; best = node; }
  }
  select(best);
}

function select(node) {
  selected = node;
  const panel = $("detail");
  if (!node) { panel.hidden = true; return; }
  node.lit = 1;
  const body = $("detail-body");
  const rows = [];
  const row = (k, v) => { if (v != null && v !== "") rows.push(el("dt", { text: k }), el("dd", { text: String(v) })); };
  const about = node.about || {};
  if (node.kind === "person") {
    row("Status", about.online ? "online now" : "last seen " + ago(about.seen));
    row("Talks through", node.device === "browser" ? "a browser" : node.device === "phone" ? "the app on a phone" : "not heard from yet");
    if (about.open_work) row("Jobs in hand", about.open_work);
    if (about.waiting) row("Waiting on them", about.waiting);
    if (about.company_id && nodes.has("c:" + about.company_id)) row("Place", nodes.get("c:" + about.company_id).name);
    if (about.reports_to && nodes.has("p:" + about.reports_to)) row("Answers to", nodes.get("p:" + about.reports_to).name);
    if (about.near && nodes.has("c:" + about.near)) row("Writes to", nodes.get("c:" + about.near).name);
  } else if (node.kind === "company") {
    row("Kind of place", about.kind_name || "not said yet");
    row("Its users may write", about.open_door ? "yes, the door is open" : "no, the door is shut");
    row("People", about.people);
    row("Issues not closed", about.open_issues);
    row("Assistant's rounds", about.autopilot ? "on" : "switched off");
  } else if (node.id === "server") {
    row("Up for", lasting(about.uptime_seconds));
    row("Asked in the last hour", about.requests_last_hour + " times");
    if (about.typical_ms) row("Typical answer", about.typical_ms + " ms");
  } else if (node.id === "writer") {
    row("Running", about.on ? "yes" : about.set ? "set, not answering yet" : "no model is set");
    if (about.model) row("Model", about.model);
    if (about.written) row("Summaries written", about.written);
    if (about.last_ms) row("Last one took", (about.last_ms / 1000).toFixed(1) + " s");
    if (about.busy) row("Now", "writing");
  } else if (node.id === "rounds") {
    row("Running", about.on ? "every " + lasting(about.every_seconds) : "switched off");
    row("Sent in the last day", about.today);
  } else if (node.id === "assistant") {
    row("Decides by", "fixed rules anyone can read");
    row("Bands", (about.bands || []).join(" · "));
  } else if (node.id === "mail") {
    row("Mail server", about.set ? "set" : "none: emails are only logged");
  } else if (node.id === "admin") {
    row("Who", "the platform's administrator, on the web");
  }
  const mine = feed.filter((one) => one.from === node.id || one.to === node.id).slice(0, 6);
  body.replaceChildren(
    el("h3", { text: node.name }),
    el("span", { class: "role", style: `--r: ${node.colour}`, text: node.kind === "person" ? about.role_name : node.kind === "company" ? "Place" : node.kind === "server" ? "Server" : "Service" }),
    el("dl", {}, rows),
    mine.length ? el("h4", { text: "Lately" }) : null,
    mine.length ? el("ul", {}, mine.map((one) => el("li", { style: `--k: ${KIND_COLOUR[one.kind]}`, text: `${clock(one.at)} · ${nameOf(one.from)} → ${nameOf(one.to)}: ${one.text}` }))) : null,
  );
  panel.hidden = false;
}
$("detail-close").onclick = () => select(null);

// ---------------------------------------------------------------- what moves

const nameOf = (id) => nodes.get(id)?.name || (id === "server" ? "the server" : id.startsWith("e:") ? "outside" : id);

function take(pulses, animate) {
  const now = performance.now();
  let stagger = 0;
  for (const one of pulses) {
    if (one.n != null) {
      if (one.n <= lastN) continue;
      lastN = one.n;
      recent.push(Date.now());
    }
    feed.unshift(one);
    if (feed.length > FEED_KEPT) feed.pop();
    listed(one);
    const quiet = QUIET.has(one.kind);
    const a = nodes.get(one.from), b = nodes.get(one.to);
    if (a) a.lit = Math.max(a.lit, quiet ? 0.45 : 1);
    if (animate && a && b && a !== b) {
      const dx = a.pos.x - b.pos.x, dy = a.pos.y - b.pos.y, dz = a.pos.z - b.pos.z;
      const distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
      flights.push({ from: one.from, to: one.to, colour: KIND_COLOUR[one.kind] || "#b3bac6", start: now + stagger, duration: calm ? 350 : Math.max(900, Math.min(2200, 650 + distance * 1.7)), quiet });
      stagger += calm ? 60 : 170;
      if (stagger > 2400) stagger = 2400;
    } else if (b) {
      b.lit = Math.max(b.lit, quiet ? 0.45 : 1);
    }
  }
  if (selected) select(selected);
  counted();
}

function listed(one) {
  const quiet = QUIET.has(one.kind);
  if (quiet && !everyQuestion) return;
  const item = el("li", { class: quiet ? "quiet" : "", style: `--k: ${KIND_COLOUR[one.kind] || "#b3bac6"}`, onclick: () => replay(one) },
    el("div", { class: "line" }, el("b", { text: nameOf(one.from) }), el("span", { class: "arrow", text: "→" }), el("b", { text: nameOf(one.to) }), " ", el("span", { class: "words", text: one.text })),
    el("div", { class: "under" },
      el("span", { text: clock(one.at) }),
      one.ms ? el("span", { text: (one.ms / 1000).toFixed(1) + " s" }) : null,
      one.ai ? el("span", { class: "tag ai", text: "AI" }) : null,
      one.device ? el("span", { class: "tag", text: one.device }) : null,
      one.demo ? el("span", { class: "tag demo", text: "demo" }) : null,
    ));
  const list = $("pulses");
  list.prepend(item);
  while (list.children.length > FEED_KEPT) list.lastChild.remove();
  $("feed-empty").hidden = true;
}

function relist() {
  $("pulses").replaceChildren();
  $("feed-empty").hidden = feed.length > 0;
  for (const one of [...feed].reverse()) listed(one);
}

function replay(one) {
  const a = nodes.get(one.from), b = nodes.get(one.to);
  if (a) a.lit = 1;
  if (b) b.lit = 1;
  if (a && b && a !== b) flights.push({ from: one.from, to: one.to, colour: KIND_COLOUR[one.kind] || "#b3bac6", start: performance.now(), duration: calm ? 350 : 1300, quiet: false });
  select(b || a || null);
}

function counted() {
  const cutoff = Date.now() - 60000;
  while (recent.length && recent[0] < cutoff) recent.shift();
  $("n-minute").textContent = recent.length;
}
setInterval(counted, 5000);

// ---------------------------------------------------------------- the server, live

function setLive(text, state) {
  $("live").className = "live " + state;
  $("live-text").textContent = text;
}

async function load() {
  const data = await get("/linking?since=" + mark);
  me = data.me;
  services = data.services;
  kinds = data.kinds;
  mark = Math.max(mark, data.mark);
  layout(data);
  if (data.pulses.length) take(data.pulses, false);
  $("who").textContent = me.role === "admin" ? "as the administrator: everything" : `as ${me.name}, ${me.role_name.toLowerCase()}`;
  legend();
  if (selected && !nodes.has(selected.id)) select(null);
}

async function watch() {
  if (watching) return;
  watching = true;
  try {
    while (token) {
      try {
        const answer = await get(`/linking/wait?since=${mark}&seconds=25`);
        mark = Math.max(mark, answer.mark);
        if (answer.pulses.length) take(answer.pulses, true);
        setLive("Live", "");
      } catch (error) {
        if (!token) return;
        setLive("Reconnecting", "down");
        await sleep(3000);
      }
    }
  } finally {
    watching = false;
  }
}

// The whole graph again now and then: who has come online, what each service says of itself.
setInterval(() => { if (token && !document.hidden) load().catch(() => {}); }, 30000);

function legend() {
  const shown = kinds.filter((kind) => !QUIET.has(kind.key) || everyQuestion);
  $("legend").replaceChildren(
    el("span", { class: "node" }, el("i", { style: `--k: ${NODE_COLOUR.server}` }), "server"),
    el("span", { class: "node" }, el("i", { style: `--k: ${NODE_COLOUR.assistant}` }), "AI services"),
    el("span", { class: "node" }, el("i", { style: `--k: ${NODE_COLOUR.company}` }), "place"),
    ...Object.entries(ROLE_COLOUR).map(([role, colour]) => el("span", { class: "node" }, el("i", { style: `--k: ${colour}` }), role === "md" ? "managing director" : role === "technical" ? "technician" : role === "member" ? "user of a place" : role)),
    ...shown.map((kind) => el("span", { title: kind.name }, el("i", { style: `--k: ${KIND_COLOUR[kind.key]}` }), KIND_SHORT[kind.key] || kind.key)),
  );
}

// ---------------------------------------------------------------- the demo: a made-up walk through the real nodes

function demoCast() {
  const people = [...nodes.values()].filter((node) => node.kind === "person");
  const by = (role) => people.filter((node) => node.role === role);
  const firm = [...nodes.values()].find((node) => node.kind === "company");
  const within = (list) => (firm ? list.filter((node) => node.about.company_id === firm.about.id || node.about.near === firm.about.id) : list);
  const one = (role) => within(by(role))[0] || by(role)[0] || null;
  const inspector = one("inspector") || one("manager") || one("md");
  const manager = one("manager") || one("md");
  const technician = one("technical") || manager;
  const user = one("member");
  return { firm, inspector, manager, technician, user, md: one("md") };
}

function demoScript() {
  const cast = demoCast();
  const title = "Water leaking on the stairs near the lift";
  const placeName = cast.firm ? cast.firm.name : "the place";
  const model = services?.writer?.model || "gemma-3-1b-it";
  const steps = [];
  const step = (kind, from, to, text, extra = {}) => { if (from && to) steps.push({ kind, from: from.id, to: to.id, text, ...extra }); };
  const S = nodes.get("server"), A = nodes.get("assistant"), Wr = nodes.get("writer"), Ro = nodes.get("rounds"), M = nodes.get("mail");
  if (cast.user) step("sent_in", cast.user, S, `sent in an issue to ${placeName}: “${title}”, with a photo`, { ai: true, device: "phone" });
  else if (cast.inspector) step("walk", cast.inspector, S, `raised “${title}” at ${placeName}`, { ai: true, device: "phone" });
  if (cast.inspector) {
    step("route", A, cast.inspector, cast.user ? `chose ${cast.inspector.name} (${cast.inspector.about.role_name.toLowerCase()}, least waiting) and ranked it Soon (48)` : `ranked “${title}” Soon (48): on ${(cast.manager || cast.inspector).name}'s desk to give out`, { ai: true });
    step("push", S, cast.inspector, "the desk changed: the phone is told and reads it again", { device: "phone" });
    step("listen", cast.inspector, S, "listening for the desk to change, up to 25 s", { device: "phone" });
  }
  step("model", S, Wr, `asked ${model} to summarise “${title}”`, { ai: true });
  step("model", Wr, S, `${model} summarised “${title}” in 3.2 s`, { ai: true, ms: 3200 });
  if (cast.inspector && cast.manager && cast.inspector !== cast.manager) {
    step("message", cast.inspector, cast.manager, `sent “${title}” to ${cast.manager.name}: “Please give this out, the stairs are slippery”`);
    step("push", S, cast.manager, "the desk changed: the phone is told and reads it again", { device: "phone" });
  }
  if (cast.manager && cast.technician && cast.technician !== cast.manager) {
    step("assign", cast.manager, cast.technician, `gave “${title}” to ${cast.technician.name}, due tomorrow, with a note`);
    step("mail", M, cast.technician, "emailed “DeskLess: an issue for you”");
    step("push", S, cast.technician, "the desk changed: the phone is told and reads it again", { device: "phone" });
    step("rounds", Ro, cast.technician, `on its rounds, asked ${cast.technician.name} about a job past its day`, { ai: true });
    step("fixed", cast.technician, cast.manager, `marked “${title}” fixed, with a photo of the repair; ${cast.manager.name} is to check it`, { device: "phone" });
    step("closed", cast.manager, cast.user || cast.inspector, `checked the repair and closed “${title}”: the stairs are dry`);
  }
  if (cast.user) step("push", S, cast.user, "the user's phone is told: repaired, checked and closed", { device: "phone" });
  return steps;
}

function playDemo() {
  if (demo) { stopDemo(); return; }
  const steps = demoScript();
  if (!steps.length) { note("There is nothing to walk through yet: the graph has no people.", true); return; }
  let at = 0;
  $("demo-banner").hidden = false;
  $("demo").classList.add("on");
  $("demo").textContent = "Stop the demo";
  const next = () => {
    const one = steps[at % steps.length];
    at++;
    take([{ ...one, n: null, at: new Date().toISOString(), demo: true }], true);
    demo = setTimeout(next, at % steps.length === 0 ? 4200 : 1900);
  };
  next();
}
function stopDemo() {
  clearTimeout(demo);
  demo = null;
  $("demo-banner").hidden = true;
  $("demo").classList.remove("on");
  $("demo").textContent = "Play a demo";
}
$("demo").onclick = playDemo;

$("turn").onclick = () => {
  camera.turning = !camera.turning;
  camera.touched = 0;
  $("turn").classList.toggle("on", camera.turning);
  $("turn").textContent = camera.turning ? "Turning" : "Still";
};
$("feed-toggle").onclick = () => { $("feed").classList.toggle("off"); fit(); };
$("every-question").onchange = (event) => { everyQuestion = event.target.checked; relist(); legend(); };

// ---------------------------------------------------------------- signing in and out

function signOut() {
  token = "";
  store.removeItem("deskless-token");
  stopDemo();
  drawing = false;
  nodes.clear();
  feed.length = 0;
  flights.length = 0;
  mark = 0;
  lastN = 0;
  $("pulses").replaceChildren();
  show();
}
$("signout").onclick = signOut;

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
    });
    token = answer.token;
    store.setItem("deskless-token", token);
    $("password").value = "";
    await show();
  } catch (error) {
    $("signin-error").textContent = error.message;
  }
};

async function show() {
  let inside = false;
  if (token) {
    try {
      await load();
      inside = true;
    } catch (error) {
      if (token) $("signin-error").textContent = error.message;
    }
  }
  $("signin").hidden = inside;
  $("stage").hidden = !inside;
  if (!inside) return;
  resize();
  if (!drawing) {
    drawing = true;
    lastFrame = performance.now();
    requestAnimationFrame(frame);
  }
  setLive("Live", "");
  watch();
}

document.addEventListener("visibilitychange", () => {
  if (!token) return;
  if (document.hidden) { drawing = false; return; }
  if (!drawing) { drawing = true; lastFrame = performance.now(); requestAnimationFrame(frame); }
});
window.addEventListener("resize", () => { if (drawing) resize(); });

show();
