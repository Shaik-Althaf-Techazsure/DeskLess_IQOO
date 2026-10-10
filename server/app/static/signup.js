"use strict";
// Signing up in a browser: the form, the code sent by email, and the wait for the person above.
// The same steps and the same checks as in the app; the server decides everything again.

const $ = (pick) => document.querySelector(pick);
const KEPT = "deskless-signup";
const STEPS = ["form", "code", "wait", "done", "no"];

/** What a sign-up needs to ask how it is doing: the email address and the pass the server gave for it. */
const kept = {
  read() {
    try { return JSON.parse(localStorage.getItem(KEPT)) || null; } catch (broken) { return null; }
  },
  write(value) {
    try { localStorage.setItem(KEPT, JSON.stringify(value)); } catch (refused) { /* then this tab alone remembers */ }
    this.now = value;
  },
  clear() {
    try { localStorage.removeItem(KEPT); } catch (refused) { /* nothing was kept */ }
    this.now = null;
  },
  now: null,
};

async function ask(path, options) {
  let answer;
  try {
    answer = await fetch(path, options);
  } catch (offline) {
    throw new Error("The server could not be reached. Check the connection and try again.");
  }
  const body = await answer.json().catch(() => ({}));
  if (!answer.ok) {
    const refused = new Error(typeof body.detail === "string" ? body.detail : "That did not work. Check what you typed and try again.");
    refused.status = answer.status;
    throw refused;
  }
  return body;
}

const post = (path, body) => ask(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

function show(step) {
  for (const name of STEPS) $(`#step-${name}`).hidden = name !== step;
  window.scrollTo({ top: 0 });
}

async function busy(button, work) {
  button.disabled = true;
  try { await work(); } finally { button.disabled = false; }
}

// ---------------------------------------------------------------- the form

const role = () => document.querySelector("input[name=role]:checked").value;
const SAYS = {
  md: "You bring your company to DeskLess and see everything in it.",
  manager: "You approve your team, give issues out and check the repairs. You need the company code from your managing director.",
  inspector: "You walk the place and report what you find. You need the team code of your manager.",
  technical: "You repair what is reported and prove it with a photo. You need the team code of your manager.",
};

function roleChosen() {
  const lead = role() === "md";
  $("#for-md").hidden = !lead;
  $("#for-team").hidden = lead;
  $("#role-says").textContent = SAYS[role()];
  whoseCode();
}
document.querySelectorAll("input[name=role]").forEach((pill) => pill.addEventListener("change", roleChosen));

// The code says which company and person the newcomer is about to join, before anything is sent.
let looking = 0;
function whoseCode() {
  const says = $("#invite-says");
  const typed = $("#invite").value.replace(/[^a-z0-9]/gi, "");
  clearTimeout(looking);
  says.className = "hint small";
  if (role() === "md" || typed.length < 8) {
    says.textContent = "From the person you will report to. They find it on their dashboard.";
    return;
  }
  looking = setTimeout(async () => {
    try {
      const found = await ask(`api/signup/invite?code=${encodeURIComponent(typed)}&role=${role()}`);
      says.textContent = `You are joining ${found.company}. ${found.lead} (${found.lead_role}) approves your sign-up.`;
      says.className = "hint small good";
    } catch (error) {
      says.textContent = error.message;
      says.className = "hint small bad";
    }
  }, 350);
}
$("#invite").addEventListener("input", whoseCode);

// The photo is made smaller here, so a picture straight from a phone's camera is not too large to send.
let photo = null;
$("#photo").addEventListener("change", async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  photo = await smaller(file).catch(() => file);
  const face = $("#face");
  face.style.backgroundImage = `url(${URL.createObjectURL(photo)})`;
  face.classList.add("has");
});

async function smaller(file, edge = 1200) {
  const picture = await createImageBitmap(file, { imageOrientation: "from-image" });
  const scale = Math.min(1, edge / Math.max(picture.width, picture.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(picture.width * scale);
  canvas.height = Math.round(picture.height * scale);
  canvas.getContext("2d").drawImage(picture, 0, 0, canvas.width, canvas.height);
  return new Promise((done, failed) => canvas.toBlob((blob) => (blob ? done(blob) : failed(new Error("no picture"))), "image/jpeg", 0.86));
}

// The check digit of an Aadhaar number (Verhoeff), so a mistyped digit is caught before it is sent.
const D = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5], [2, 3, 4, 0, 1, 7, 8, 9, 5, 6], [3, 4, 0, 1, 2, 8, 9, 5, 6, 7], [4, 0, 1, 2, 3, 9, 5, 6, 7, 8],
  [5, 9, 8, 7, 6, 0, 4, 3, 2, 1], [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3], [8, 7, 6, 5, 9, 3, 2, 1, 0, 4], [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]];
const P = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4], [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
  [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1], [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]];
function aadhaarOk(digits) {
  if (!/^[2-9]\d{11}$/.test(digits)) return false;
  let check = 0;
  [...digits].reverse().forEach((digit, place) => { check = D[check][P[place % 8][Number(digit)]]; });
  return check === 0;
}

/** What is wrong with the form, in one sentence, or nothing. */
function wrong(form) {
  if (!photo) return "Add a photo of yourself.";
  if (form.name.length < 2) return "Enter your full name.";
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(form.email)) return "That email address does not look right.";
  if (!/^[6-9]\d{9}$/.test(form.phone)) return "Enter a 10-digit mobile number.";
  if (form.role === "md" && form.organisation.length < 2) return "Enter your company's name.";
  if (form.role !== "md" && form.invite.length < 8) return "Enter the code from the person you report to. They find it on their dashboard.";
  if (!aadhaarOk(form.aadhaar)) return "That Aadhaar number is not valid. Check the 12 digits.";
  if (form.password.length < 8) return "Choose a password of at least 8 characters.";
  if (form.password !== $("#again").value) return "The two passwords are not the same.";
  return "";
}

$("#form").addEventListener("submit", (event) => {
  event.preventDefault();
  const form = {
    role: role(),
    name: $("#name").value.trim().replace(/\s+/g, " "),
    email: $("#email").value.trim().toLowerCase(),
    phone: $("#phone").value.replace(/[\s\-()]/g, "").replace(/^(\+91|0)/, ""),
    organisation: $("#organisation").value.trim(),
    invite: $("#invite").value.replace(/[^a-z0-9]/gi, ""),
    aadhaar: $("#aadhaar").value.replace(/[\s-]/g, ""),
    password: $("#password").value,
  };
  const error = $("#form-error");
  error.textContent = wrong(form);
  if (error.textContent) return;
  busy($("#form-send"), async () => {
    const send = new FormData();
    for (const [key, value] of Object.entries(form)) send.append(key, value);
    send.append("photo", photo, "me.jpg");
    try {
      const made = await ask("api/signup", { method: "POST", body: send });
      kept.write({ email: made.email, ticket: made.ticket });
      toTheCode(made.code_minutes);
    } catch (refused) {
      error.textContent = refused.message;
    }
  });
});

// ---------------------------------------------------------------- the code

function toTheCode(minutes) {
  $("#code-to").textContent = kept.now.email;
  if (minutes) $("#code-minutes").textContent = minutes;
  $("#code").value = "";
  $("#code-error").textContent = "";
  show("code");
  $("#code").focus();
}

$("#code-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const code = $("#code").value.replace(/\D/g, "");
  const error = $("#code-error");
  if (code.length !== 6) { error.textContent = "Type the 6 digits of the code."; return; }
  busy(event.submitter || $("#code-form button"), async () => {
    try {
      await post("api/signup/verify", { email: kept.now.email, code });
      await how();
    } catch (refused) {
      error.textContent = refused.message;
    }
  });
});

$("#code-again").addEventListener("click", (event) => busy(event.currentTarget, async () => {
  const error = $("#code-error");
  try {
    await post("api/signup/resend", { email: kept.now.email });
    error.textContent = "A new code is on its way.";
  } catch (refused) {
    error.textContent = refused.message;
  }
}));

function startAgain() {
  kept.clear();
  clearInterval(watching);
  $("#form-error").textContent = "";
  show("form");
}
$("#code-back").addEventListener("click", startAgain);
$("#wait-forget").addEventListener("click", startAgain);
$("#no-again").addEventListener("click", startAgain);

// ---------------------------------------------------------------- the wait, and how it ends

let watching = 0;

/** Asks the server how the sign-up is doing and shows the step that goes with the answer. */
async function how() {
  const { email, ticket } = kept.now;
  const about = await ask(`api/signup/status?email=${encodeURIComponent(email)}&ticket=${encodeURIComponent(ticket)}`);
  clearInterval(watching);
  if (about.status === "email_pending") {
    toTheCode();
  } else if (about.status === "pending_approval") {
    const who = about.approver || "the DeskLess administrator";
    $("#wait-who").textContent = who.charAt(0).toUpperCase() + who.slice(1);
    $("#wait-error").textContent = "";
    show("wait");
    watching = setInterval(() => { if (!document.hidden) how().catch(() => {}); }, 20000);
  } else if (about.status === "approved") {
    $("#done-id").textContent = about.unique_id;
    show("done");
    kept.clear();
  } else {
    $("#no-why").textContent = about.reason || "Your sign-up was not approved. Ask the person you report to, then sign up again.";
    show("no");
    kept.clear();
  }
}

$("#wait-check").addEventListener("click", (event) => busy(event.currentTarget, async () => {
  try {
    await how();
    if (!$("#step-wait").hidden) $("#wait-error").textContent = "Not decided yet.";
  } catch (refused) {
    $("#wait-error").textContent = refused.message;
  }
}));

$("#done-id").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("#done-id").textContent);
    $("#done-says").textContent = "Copied. Sign in with this ID and the password you chose.";
  } catch (refused) { /* it can still be read and typed */ }
});

// ---------------------------------------------------------------- arriving

roleChosen();
kept.now = kept.read();
if (kept.now && kept.now.email && kept.now.ticket) {
  // Somebody who signed up here earlier comes back to where their sign-up stands.
  how().catch((refused) => {
    if (refused.status === 404) { startAgain(); return; }
    // The server could not be asked just now: the sign-up is kept, and can be asked about again.
    $("#wait-who").textContent = "The person above you";
    $("#wait-error").textContent = refused.message;
    show("wait");
  });
} else {
  show("form");
}
