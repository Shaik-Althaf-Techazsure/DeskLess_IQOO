"use strict";
// The home page: the menu, the film, the deck, the pictures. Nothing here talks to the server.

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
play.addEventListener("click", () => {
  // The browser's own controls take over once the film runs.
  video.controls = true;
  video.play();
});
video.addEventListener("play", () => { play.hidden = true; });
video.addEventListener("ended", () => { play.hidden = false; });

// ---------------------------------------------------------------- the screens of the app
const reel = $("#reel");
const along = (way) => reel.scrollBy({ left: way * Math.max(260, reel.clientWidth * 0.7), behavior: "smooth" });
$("#reel-back").addEventListener("click", () => along(-1));
$("#reel-on").addEventListener("click", () => along(1));

// ---------------------------------------------------------------- the consoles, one at a time
const tabs = $$(".tabs [role=tab]");
tabs.forEach((tab) => tab.addEventListener("click", () => {
  for (const other of tabs) {
    other.setAttribute("aria-selected", String(other === tab));
    document.getElementById(other.dataset.show).hidden = other !== tab;
  }
}));

// ---------------------------------------------------------------- the deck
const slide = $("#slide");
const thumbs = $$("#thumbs button");
const count = $("#deck-count");
let at = 0;

function show(to) {
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
}

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
