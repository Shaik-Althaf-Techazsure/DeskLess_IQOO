"use strict";
// The home page: the menu, the film, the deck, the pictures, and the form that asks whether DeskLess
// fits. Only that form talks to the server.
// The film, the deck and the business model canvas are on the page only when their files are there,
// so each part is looked for before it is given its buttons.

const $ = (pick, within = document) => within.querySelector(pick);
const $$ = (pick, within = document) => [...within.querySelectorAll(pick)];
document.documentElement.classList.add("js");

// ---------------------------------------------------------------- the top bar
const links = $("#links");
const menu = $("#menu");
menu.addEventListener("click", () => {
  const open = links.classList.toggle("open");
  menu.setAttribute("aria-expanded", String(open));
});
links.addEventListener("click", (event) => {
  if (event.target.closest("a")) {
    links.classList.remove("open");
    menu.setAttribute("aria-expanded", "false");
  }
});

// Somebody who signed in on the dashboard in this tab is not asked to sign in again.
try {
  if (sessionStorage.getItem("deskless-token")) {
    $("#nav-in").textContent = "My dashboard";
    $("#nav-in").classList.replace("ghost", "solid");
    $("#nav-up").hidden = true;
  }
} catch (refused) { /* a browser that keeps nothing: the two buttons stay as they are */ }

// The section being read is marked in the menu.
const marks = new Map($$(".links a").map((link) => [link.getAttribute("href").slice(1), link]));
const reading = new IntersectionObserver((seen) => {
  for (const entry of seen) {
    if (entry.isIntersecting) {
      for (const link of marks.values()) link.classList.remove("here");
      marks.get(entry.target.id)?.classList.add("here");
    }
  }
}, { rootMargin: "-45% 0px -50% 0px" });
for (const id of marks.keys()) {
  const section = document.getElementById(id);
  if (section) reading.observe(section);
}

// ---------------------------------------------------------------- arriving as the page is read
const arriving = new IntersectionObserver((seen) => {
  for (const entry of seen) {
    if (entry.isIntersecting) {
      entry.target.classList.add("in");
      arriving.unobserve(entry.target);
    }
  }
}, { rootMargin: "0px 0px -40px 0px" });
$$(".reveal").forEach((part) => arriving.observe(part));

// ---------------------------------------------------------------- the film
const video = $("#video");
const play = $("#play");
if (video && play) {
  play.addEventListener("click", () => {
    // The browser's own controls take over once the film runs.
    video.controls = true;
    video.play();
  });
  video.addEventListener("play", () => { play.hidden = true; });
  video.addEventListener("ended", () => { play.hidden = false; });
}

// ---------------------------------------------------------------- rows of screens, moved along by their arrows
for (const row of $$(".reel-wrap")) {
  const reel = $(".reel", row);
  const along = (way) => reel.scrollBy({ left: way * Math.max(260, reel.clientWidth * 0.7), behavior: "smooth" });
  $(".reel-back", row)?.addEventListener("click", () => along(-1));
  $(".reel-on", row)?.addEventListener("click", () => along(1));
}

// ---------------------------------------------------------------- one side of the product at a time
for (const group of $$(".tabs")) {
  const tabs = $$("[role=tab]", group);
  tabs.forEach((tab) => tab.addEventListener("click", () => {
    for (const other of tabs) {
      other.setAttribute("aria-selected", String(other === tab));
      document.getElementById(other.dataset.show).hidden = other !== tab;
    }
    tab.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "center" });
  }));
}

// ---------------------------------------------------------------- the deck
const slide = $("#slide");
const thumbs = $$("#thumbs button");
if (slide && thumbs.length) {
  const count = $("#deck-count");
  let at = 0;

  const show = (to) => {
    at = (to + thumbs.length) % thumbs.length;
    const chosen = thumbs[at];
    slide.classList.add("away");
    const next = new Image();
    next.onload = next.onerror = () => {
      slide.src = chosen.dataset.full;
      slide.alt = `Slide ${at + 1} of ${thumbs.length}`;
      slide.classList.remove("away");
    };
    next.src = chosen.dataset.full;
    thumbs.forEach((thumb, n) => {
      if (n === at) thumb.setAttribute("aria-current", "true");
      else thumb.removeAttribute("aria-current");
    });
    count.textContent = `${at + 1} / ${thumbs.length}`;
    // The row of small slides follows, without moving the page itself.
    const row = chosen.parentElement;
    row.scrollTo({ left: chosen.offsetLeft - row.clientWidth / 2 + chosen.clientWidth / 2, behavior: "smooth" });
  };

  thumbs.forEach((thumb, n) => thumb.addEventListener("click", () => show(n)));
  $("#deck-back").addEventListener("click", () => show(at - 1));
  $("#deck-on").addEventListener("click", () => show(at + 1));
  $("#deck-view").addEventListener("keydown", (event) => {
    if (event.key === "ArrowLeft") show(at - 1);
    if (event.key === "ArrowRight") show(at + 1);
  });
  // A finger moved across the slide turns it.
  let from = null;
  const stage = $(".stage");
  stage.addEventListener("touchstart", (event) => { from = event.touches[0].clientX; }, { passive: true });
  stage.addEventListener("touchend", (event) => {
    if (from === null) return;
    const moved = event.changedTouches[0].clientX - from;
    from = null;
    if (Math.abs(moved) > 44) show(at + (moved < 0 ? 1 : -1));
  }, { passive: true });
}

// ---------------------------------------------------------------- will it work for you: the form and its answer
// The one part of the page that talks to the server. What somebody needs goes to "api/fit"; the answer
// is decided there, from what the product does, and only shown here. Everything is put on the page as
// text, never as markup.
const fitForm = $("#fit-form");
if (fitForm) {
  const say = async (path, body) => {
    let answer;
    try {
      answer = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    } catch (offline) {
      throw new Error("The server could not be reached. Check the connection and try again.");
    }
    const told = await answer.json().catch(() => ({}));
    if (!answer.ok) throw new Error(typeof told.detail === "string" ? told.detail : "That did not work. Try again.");
    return told;
  };
  const make = (tag, text, name) => {
    const part = document.createElement(tag);
    part.textContent = text;
    if (name) part.className = name;
    return part;
  };
  const LOOK = { yes: "good", yes_but: "", partly: "", no: "no", likely: "ask", unclear: "ask" };
  const text = $("#fit-text");
  const ask = $("#fit-ask");
  const answer = $("#fit-answer");
  let told = null;

  $$(".tries button").forEach((one) => one.addEventListener("click", () => {
    text.value = one.dataset.try;
    text.focus();
  }));

  const show = (reply, asked) => {
    told = reply;
    $("#fit-you").textContent = asked;
    const headline = $("#fit-headline");
    headline.textContent = reply.headline;
    headline.className = `verdict arrives ${LOOK[reply.verdict] || ""}`;
    $("#fit-summary").textContent = reply.summary;
    const points = $("#fit-points");
    points.replaceChildren();
    for (const [title, list, name] of [
      ["What DeskLess would do for you", reply.does, ""],
      ["What to know first", reply.limits, "limits"],
      ["What it does not do", reply.not, "nots"],
      ["How you would start", reply.start.map((says) => ({ says })), "begin"],
    ]) {
      if (!list.length) continue;
      const block = make("div", "", `points arrives ${name}`);
      block.append(make("h5", title), ...list.map((point) => make("p", point.says)));
      points.append(block);
    }
    $("#fit-engine").textContent = reply.engine ? `worded by ${reply.engine} on this server` : "answered from what the product does";
    // The brochure is offered where there is something in it for them; a plain no is not followed by a sales letter.
    const fits = ["yes", "yes_but", "partly", "likely"].includes(reply.verdict);
    $("#fit-want").hidden = !fits;
    $("#fit-more").textContent = fits ? "Let me add something" : "Let me say it differently";
    $("#fit-wa").href = $("#fit-wa-2").href = reply.whatsapp;
    $("#fit-next").hidden = false;
    $("#fit-mail").hidden = $("#fit-sent").hidden = true;
    fitForm.hidden = true;
    answer.hidden = false;
    $("#fit").scrollIntoView({ behavior: "smooth", block: "nearest" });
  };

  fitForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const asked = text.value.trim();
    const error = $("#fit-error");
    error.textContent = "";
    if (asked.length < 12) { error.textContent = "Tell me a little more: what goes wrong, and in what kind of place?"; return; }
    ask.disabled = true;
    ask.classList.add("thinking");
    try {
      show(await say("api/fit", { text: asked, place: $("#fit-place").value }), asked);
    } catch (refused) {
      error.textContent = refused.message;
    } finally {
      ask.disabled = false;
      ask.classList.remove("thinking");
    }
  });

  const back = () => {
    answer.hidden = true;
    fitForm.hidden = false;
    text.focus();
  };
  $("#fit-more").addEventListener("click", back);
  $("#fit-again").addEventListener("click", () => { text.value = ""; back(); });
  $("#fit-want").addEventListener("click", () => {
    $("#fit-mail").hidden = false;
    $("#fit-want").hidden = true;
    $("#fit-email").focus();
  });

  $("#fit-mail").addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = $("#fit-email").value.trim();
    const error = $("#fit-mail-error");
    const send = $("#fit-send");
    error.textContent = "";
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(email)) { error.textContent = "That email address does not look right."; return; }
    send.disabled = true;
    send.classList.add("thinking");
    try {
      const sent = await say("api/fit/brochure", { ticket: told.ticket, email, name: $("#fit-name").value });
      $("#fit-sent-line").textContent = sent.sent ? "Sent. Look in your inbox." : "Here it is.";
      $("#fit-sent-more").textContent = sent.sent
        ? `The brochure is on its way to ${sent.email}, with the answer above and a link to us on WhatsApp for the price. If it is not there in a minute, look in the spam folder.`
        : "This server has no mail service just now, so the brochure is this page itself; the price is one message away on WhatsApp.";
      const deck = $("#fit-deck");
      deck.hidden = !sent.deck;
      if (sent.deck) deck.href = sent.deck;
      $("#fit-wa-2").href = sent.whatsapp;
      $("#fit-mail").hidden = $("#fit-next").hidden = true;
      $("#fit-sent").hidden = false;
    } catch (refused) {
      error.textContent = refused.message;
    } finally {
      send.disabled = false;
      send.classList.remove("thinking");
    }
  });
}

// ---------------------------------------------------------------- a picture, enlarged
const zoom = $("#zoom");
const large = $("img", zoom);
$$(".shot").forEach((shot) => shot.addEventListener("click", () => {
  const small = $("img", shot);
  large.src = shot.dataset.zoom;
  large.alt = small ? small.alt : "";
  // A phone's screen is fitted to the height of the window; a long page of a dashboard is scrolled.
  zoom.classList.toggle("tall", shot.classList.contains("phone") || shot.classList.contains("sheet"));
  zoom.showModal();
  zoom.scrollTop = 0;
}));
zoom.addEventListener("click", () => zoom.close());
zoom.addEventListener("close", () => { large.removeAttribute("src"); });
