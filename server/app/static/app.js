"use strict";

// One page for every role. After signing in, the server says who the person is, and the page shows the
// tabs, numbers and actions that belong to that role. What each may see is decided by the server; the
// page only decides how to show it.

const $ = (id) => document.getElementById(id);
const store = sessionStorage;
// The page may be served under a path on its domain; the API is beside the page, wherever that is.
const BASE = new URL(".", location.href).pathname;

let token = store.getItem("deskless-token") || "";
let dash = null;            // what the server last said about this person's numbers
let view = "";              // the tab that is open; a role's first tab until one is chosen
let issues = [];
let issueFilter = "";
let issueSearch = "";
let ofReport = null;        // when set, only the issues of this report are listed
let peopleStage = "approved";

const NAV = {
  admin: [["overview", "Overview"], ["approvals", "Approvals"], ["companies", "Companies"], ["people", "People"], ["reports", "Reports"], ["issues", "Issues"]],
  md: [["overview", "Overview"], ["approvals", "Approvals"], ["issues", "Issues"], ["reports", "Reports"], ["people", "People"]],
  manager: [["overview", "Overview"], ["approvals", "Approvals"], ["issues", "Issues"], ["reports", "Reports"], ["people", "My team"]],
  inspector: [["overview", "Overview"], ["reports", "My reports"], ["issues", "My issues"]],
  technical: [["issues", "My jobs"], ["overview", "Overview"]],
};
const STATUS = { open: "To give out", assigned: "With technician", fixed: "Fixed, to check", closed: "Closed" };
const SEVERITY = { CRITICAL: "Critical", HIGH: "High", MEDIUM: "Medium", LOW: "Low" };
const SEVERITY_COLOUR = { CRITICAL: "var(--red)", HIGH: "var(--orange)", MEDIUM: "var(--yellow)", LOW: "var(--blue)" };
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
  const minutes = Math.round((Date.now() - new Date(iso)) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return minutes + " min ago";
  if (minutes < 24 * 60) return Math.round(minutes / 60) + " h ago";
  return when(iso);
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

const chip = (text, kind = "") => el("span", { class: "chip " + kind, text });
const statusChip = (issue) => chip(dash.me.role === "technical" && issue.status === "assigned" ? "To do" : STATUS[issue.status] || issue.status, issue.status);

// ---------------------------------------------------------------- signing in and out

function signOut() {
  token = "";
  dash = null;
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
  $("view").hidden = !inside;
  $("nav").hidden = !inside;
  $("who").hidden = !inside;
  $("company").textContent = inside ? dash.me.organisation : "";
  if (!inside) return;
  $("who-name").textContent = dash.me.name;
  $("who-role").textContent = [dash.me.role_name, dash.me.unique_id].filter(Boolean).join(" · ");
  if (!NAV[dash.me.role].some(([key]) => key === view)) view = NAV[dash.me.role][0][0];
  await load();
}

function drawNav() {
  const tiles = dash.tiles;
  const role = dash.me.role;
  // The number beside a tab is what waits there for this person to act on.
  const waiting = {
    approvals: tiles.waiting_people,
    issues: role === "technical" ? tiles.my_jobs : dash.may_direct ? tiles.open + tiles.fixed : 0,
  };
  $("nav").replaceChildren(...NAV[role].map(([key, label]) =>
    el("button", { class: key === view ? "on" : "", onclick: () => go(key) }, label, waiting[key] ? el("span", { class: "n", text: waiting[key] }) : null)));
}

function go(to, filter) {
  view = to;
  if (to !== "issues" || filter !== undefined) ofReport = null;
  if (filter !== undefined) issueFilter = filter;
  window.scrollTo(0, 0);
  load();
}

// ---------------------------------------------------------------- loading and drawing a view

let loading = 0;
async function load() {
  const mine = ++loading;
  try {
    dash = await get("/dashboard");
    let content;
    if (view === "overview") content = overview(dash.me.role === "admin" ? await get("/admin/companies") : null);
    else if (view === "approvals") content = approvals(await get("/people?status=pending_approval"));
    else if (view === "people") content = people(await get("/people?status=" + peopleStage));
    else if (view === "companies") content = companies(await get("/admin/companies"));
    else if (view === "reports") content = reports(await get("/reports"));
    else {
      issues = await get("/issues" + (ofReport ? "?report=" + encodeURIComponent(ofReport.id) : ""));
      content = issuesView();
    }
    if (mine !== loading) return;     // a newer request has overtaken this one
    drawNav();
    $("view").replaceChildren(...content);
  } catch (error) {
    if (token) note(error.message, true);
  }
}

function head(title, sub, ...extra) {
  return el("div", { class: "head" }, el("div", { class: "grow" }, el("h2", { text: title }), sub ? el("p", { text: sub }) : null), ...extra);
}

// ---------------------------------------------------------------- overview

function tile(label, value, options = {}) {
  const inside = [el("b", { text: value }), el("span", { text: label }), options.small ? el("small", { text: options.small }) : null];
  return options.go
    ? el("button", { class: "tile " + (options.tone || ""), onclick: options.go }, ...inside)
    : el("div", { class: "tile " + (options.tone || "") }, ...inside);
}

function tilesFor(role, t, allCompanies) {
  const week = count(t.reports_week, "report") + " this week";
  const signups = tile("Sign-ups waiting for you", t.waiting_people, { tone: t.waiting_people ? "yellow" : "", go: () => go("approvals") });
  const overdue = tile("Overdue", t.overdue, { tone: t.overdue ? "red" : "", go: () => go("issues", "overdue") });
  if (role === "admin") return [
    tile("Companies", allCompanies.length, { go: () => go("companies") }),
    signups,
    tile("Reports", t.reports, { small: week, go: () => go("reports") }),
    tile("Issues not closed", t.unsettled, { go: () => go("issues", "unsettled") }),
    overdue,
  ];
  if (role === "md") return [
    tile("Reports", t.reports, { small: week, go: () => go("reports") }),
    tile("Issues not closed", t.unsettled, { go: () => go("issues", "unsettled") }),
    tile("High or critical", t.serious, { tone: t.serious ? "red" : "", go: () => go("issues", "serious") }),
    overdue,
    tile("Fixed, to check", t.fixed, { tone: t.fixed ? "yellow" : "", go: () => go("issues", "fixed") }),
    tile("Closed", t.closed, { tone: "green", go: () => go("issues", "closed") }),
    signups,
  ];
  if (role === "manager") return [
    tile("To give out", t.open, { tone: t.open ? "yellow" : "", go: () => go("issues", "open") }),
    tile("With technicians", t.assigned, { tone: "blue", go: () => go("issues", "assigned") }),
    tile("Fixed, to check", t.fixed, { tone: t.fixed ? "yellow" : "", go: () => go("issues", "fixed") }),
    overdue,
    tile("Reports", t.reports, { small: week, go: () => go("reports") }),
    signups,
  ];
  if (role === "inspector") return [
    tile("My reports", t.reports, { small: week, go: () => go("reports") }),
    tile("Issues I reported", t.issues, { go: () => go("issues", "all") }),
    tile("Not closed yet", t.unsettled, { go: () => go("issues", "unsettled") }),
    tile("Being fixed", t.assigned + t.fixed, { tone: "blue", go: () => go("issues", "assigned") }),
    tile("Closed", t.closed, { tone: "green", go: () => go("issues", "closed") }),
  ];
  return [
    tile("To do", t.my_jobs, { tone: t.my_jobs ? "yellow" : "", go: () => go("issues", "todo") }),
    tile("Overdue", t.my_overdue, { tone: t.my_overdue ? "red" : "", go: () => go("issues", "todo") }),
    tile("Fixed, waiting for the check", t.my_fixed, { tone: "blue", go: () => go("issues", "fixed") }),
    tile("Done", t.my_closed, { tone: "green", go: () => go("issues", "closed") }),
  ];
}

function bars(title, rows, legend) {
  const most = Math.max(1, ...rows.map((row) => row.total));
  return el("div", { class: "card chart" }, el("h4", { text: title }),
    ...rows.map((row) => el("div", { class: "bar" },
      el("span", { class: "name", text: row.name, title: row.name }),
      el("div", { class: "track" },
        el("div", { class: "fill", style: `width:${(100 * row.open) / most}%;` + (row.colour ? `background:${row.colour}` : "") }),
        row.total > row.open ? el("div", { class: "fill rest", style: `width:${(100 * (row.total - row.open)) / most}%` }) : null),
      el("span", { class: "count", text: row.open }))),
    legend ? el("div", { class: "legend" }, el("span", {}, el("i"), "Not closed"), el("span", {}, el("i", { class: "rest" }), "Closed")) : null);
}

function trend(days) {
  const most = Math.max(1, ...days.map((one) => Math.max(one.reported, one.closed)));
  return el("div", { class: "card chart" }, el("h4", { text: "The last 14 days" }),
    el("div", { class: "columns" }, ...days.map((one) => el("div", { class: "day", title: `${day(one.day)}: ${one.reported} reported, ${one.closed} closed` },
      el("div", { class: "pair" },
        el("div", { class: "col", style: `height:${(100 * one.reported) / most}%` }),
        el("div", { class: "col done", style: `height:${(100 * one.closed) / most}%` })),
      el("span", { text: Number(one.day.slice(8)) })))),
    el("div", { class: "legend" }, el("span", {}, el("i"), "Reported"), el("span", {}, el("i", { class: "done" }), "Closed")));
}

function overview(allCompanies) {
  const me = dash.me;
  const t = dash.tiles;
  const hour = new Date().getHours();
  const greeting = (hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening") + ", " + me.name.split(" ")[0];
  const under = [me.role_name, me.organisation, me.reports_to ? "reporting to " + me.reports_to : ""].filter(Boolean).join(" · ");
  const parts = [head(greeting, under)];

  if (me.invite_code) {
    const who = me.role === "md" ? "your managers (and any inspector or technician who reports straight to you)" : "your inspectors and technicians";
    parts.push(el("div", { class: "card code" },
      el("div", {}, el("div", { class: "hint", text: me.role === "md" ? "COMPANY CODE" : "TEAM CODE" }), el("div", { class: "digits", text: me.invite_code })),
      el("p", { text: `Give this code to ${who}. They type it when they sign up in the app, and their sign-up then comes to you to approve.` }),
      el("button", { onclick: () => navigator.clipboard?.writeText(me.invite_code).then(() => note("Code copied."), () => note("Copy it by hand: " + me.invite_code)) ?? note("Copy it by hand: " + me.invite_code) }, "Copy")));
  }

  parts.push(el("div", { class: "tiles" }, ...tilesFor(me.role, t, allCompanies || [])));

  if (t.issues) {
    const severity = Object.entries(dash.by_severity).map(([name, n]) => ({ name: SEVERITY[name], open: n, total: n, colour: SEVERITY_COLOUR[name] }));
    parts.push(el("h3", { class: "section", text: "Where things stand" }),
      el("div", { class: "charts" },
        bars("Not closed, by severity", severity),
        trend(dash.trend),
        dash.by_site.length > 1 || me.role !== "technical" ? bars("By site", dash.by_site, true) : null,
        bars("By type", dash.by_type, true)));
  } else {
    parts.push(el("div", { class: "empty", text:
      me.role === "technical" ? "Nothing has been given to you yet. When your manager gives you an issue, it appears here."
        : me.role === "inspector" ? "No report yet. Build a report in the DeskLess app; it is sent here as soon as the phone has a network."
        : "No report has arrived yet. Reports are sent from the DeskLess app as soon as the phone has a network." }));
  }

  if (dash.teams.length) {
    parts.push(el("h3", { class: "section", text: "Teams" }),
      el("div", { class: "card scroll" }, el("table", {},
        el("tr", {}, ...["Led by", "Reports", "Issues", "Not closed", "Overdue", "Closed"].map((name, at) => el("th", { class: at ? "num" : "", text: name }))),
        ...dash.teams.map((team) => el("tr", {},
          el("td", { text: team.lead }),
          ...["reports", "issues", "open", "overdue", "closed"].map((key) =>
            el("td", { class: "num", text: team[key], style: key === "overdue" && team[key] ? "color:var(--red);font-weight:700" : "" })))))));
  }

  if (dash.activity.length) {
    parts.push(el("h3", { class: "section", text: "Lately" }),
      el("div", { class: "card feed" }, ...dash.activity.map((event) => {
        const inside = [el("span", { class: "dot " + event.kind }), el("span", {}, event.about ? el("em", { text: event.about + " · " }) : null, event.text), el("time", { text: ago(event.at) })];
        return event.issue_id && event.about
          ? el("button", { class: "line", onclick: () => openIssue(event.issue_id) }, ...inside)
          : el("div", { class: "line" }, ...inside);
      })));
  }
  return parts;
}

// ---------------------------------------------------------------- people: approvals, the list, companies

function facts(pairs) {
  return el("dl", {}, ...pairs.filter(([, value]) => value).flatMap(([label, value]) => [el("dt", { text: label }), el("dd", { text: value })]));
}

function person(row, actions) {
  return el("div", { class: "item" },
    picture(`/people/${row.id}/photo`, "Photo of " + row.name, true),
    el("div", {},
      el("h4", {}, row.name + " ", chip(row.role_name, "role"), row.me ? chip("You") : null),
      row.unique_id ? el("div", { class: "id", text: row.unique_id }) : null,
      facts([
        ["Email", row.email], ["Mobile", row.phone], ["Company", row.organisation], ["Reports to", row.reports_to],
        ["Aadhaar", row.aadhaar], ["Code to join under", row.status === "approved" ? row.invite_code : ""],
        [row.status === "pending_approval" ? "Email confirmed" : row.status === "rejected" ? "Rejected" : "Approved", when(row.decided_at || row.email_verified_at)],
      ]),
      row.status === "rejected" && row.reject_reason ? el("div", { class: "reason", text: "Reason: " + row.reject_reason }) : null),
    actions);
}

function approvals(waiting) {
  const who = dash.me.role === "admin" ? "Managing directors bringing a new company wait for you. You may also decide any other sign-up."
    : dash.me.role === "md" ? "The people who signed up with your company code, and anyone else waiting in your company."
    : "The people who signed up with your team code.";
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
  return [head("Approvals", who), cards.length ? el("div", { class: "list" }, ...cards) : el("div", { class: "empty", text: "Nobody is waiting for you." })];
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

function people(rows) {
  const stages = el("div", { class: "pills" }, ...[["approved", "Approved"], ["rejected", "Rejected"]].map(([key, label]) =>
    el("button", { class: key === peopleStage ? "on" : "", text: label, onclick: () => { peopleStage = key; load(); } })));
  const title = dash.me.role === "manager" ? "My team" : "People";
  // Those who lead come first, then each kind together, by name.
  const order = ["md", "manager", "inspector", "technical"];
  rows.sort((a, b) => (order.indexOf(a.role) - order.indexOf(b.role)) || a.name.localeCompare(b.name));
  const cards = rows.map((row) => {
    const hands = [
      row.role === "technical" ? count(row.open_work, "job") + " in hand" : "",
      row.reports ? count(row.reports, "report") + " sent" : "",
      row.team ? "leads " + count(row.team, "person", "people") : "",
    ].filter(Boolean);
    return person(row, el("div", { class: "side" }, ...hands.map((text) => el("span", { text }))));
  });
  return [head(title, count(rows.length, "person", "people")), stages,
    cards.length ? el("div", { class: "list" }, ...cards) : el("div", { class: "empty", text: "Nobody here yet." })];
}

function companies(rows) {
  if (!rows.length) return [head("Companies"), el("div", { class: "empty", text: "No company yet. A company appears when you approve its managing director." })];
  return [head("Companies", count(rows.length, "company", "companies")),
    el("div", { class: "card scroll" }, el("table", {},
      el("tr", {}, ...["Company", "Managing director", "People", "Reports", "Issues not closed", "Since"].map((name, at) => el("th", { class: at > 1 && at < 5 ? "num" : "", text: name }))),
      ...rows.map((row) => el("tr", {},
        el("td", { text: row.name }),
        el("td", {}, row.managing_director, " ", row.managing_director_id ? el("span", { class: "id", text: row.managing_director_id }) : null),
        el("td", { class: "num", text: row.people }), el("td", { class: "num", text: row.reports }), el("td", { class: "num", text: row.open_issues }),
        el("td", { text: when(row.created_at) })))))];
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
  const title = dash.me.role === "inspector" ? "My reports" : "Reports";
  if (!rows.length) return [head(title), el("div", { class: "empty", text: "No report yet. The DeskLess app sends each report it builds as soon as the phone has a network." })];
  return [head(title, count(rows.length, "report")), el("div", { class: "list" }, ...rows.map((row) => {
    const verdict = VERDICT[row.verdict];
    return el("div", { class: "item" },
      el("div", { class: "blank", style: "display:grid;place-items:center;font-weight:800;color:var(--fog)", text: "PDF" }),
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
  }))];
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

const SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];

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

function issueList() {
  const all = filters();
  const chosen = all.find(([key]) => key === issueFilter) || all[0];
  const words = issueSearch.trim().toLowerCase();
  const shown = issues.filter(chosen[2])
    .filter((issue) => !words || [issue.title, issue.site, issue.location, issue.category, issue.note, issue.assigned_to_name, issue.reported_by].join(" ").toLowerCase().includes(words));
  // In one report the order of the walk is kept; otherwise the most pressing come first.
  if (!ofReport) shown.sort((a, b) => (b.overdue - a.overdue) || (SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity)) || (b.captured_at > a.captured_at ? 1 : -1));
  return shown.length ? el("div", { class: "list", id: "issue-list" }, ...shown.map(issueCard))
    : el("div", { class: "empty", id: "issue-list", text: issues.length ? "Nothing here." : dash.me.role === "technical" ? "Nothing has been given to you yet." : "No issues yet." });
}

function issuesView() {
  const all = filters();
  if (!all.some(([key]) => key === issueFilter)) issueFilter = all[0][0];
  const title = ofReport ? (ofReport.place || ofReport.title) : dash.me.role === "technical" ? "My jobs" : dash.me.role === "inspector" ? "My issues" : "Issues";
  const sub = ofReport ? `${ofReport.title} · ${when(ofReport.built_at)}` : dash.me.role === "technical" ? "Open a job to see the picture. When it is done, mark it fixed with a photo of the repair."
    : dash.may_direct ? "Give each issue to a technician, then check the repair and close it." : "";
  const search = el("input", { class: "search", type: "search", placeholder: "Search issues", value: issueSearch });
  search.oninput = () => { issueSearch = search.value; $("issue-list").replaceWith(issueList()); };
  return [
    head(title, sub, ofReport ? el("button", { text: "All issues", onclick: () => go("issues", issueFilter) }) : null),
    el("div", { class: "pills" }, ...all.map(([key, label, test]) =>
      el("button", { class: key === issueFilter ? "on" : "", onclick: () => { issueFilter = key; load(); } }, label, el("span", { class: "n", text: issues.filter(test).length })))),
    search,
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
    ? el("a", { href: `https://www.google.com/maps?q=${issue.lat},${issue.lon}`, target: "_blank", rel: "noopener", style: "color:var(--blue)", text: `${issue.lat.toFixed(5)}, ${issue.lon.toFixed(5)}` })
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

// Work comes in at any moment; the page keeps itself fresh unless someone is in the middle of something.
setInterval(() => {
  const busy = $("sheet").open || $("big").open || ["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement?.tagName);
  if (token && dash && !document.hidden && !busy) load();
}, 20000);

openedFromTheApp().then(show);
