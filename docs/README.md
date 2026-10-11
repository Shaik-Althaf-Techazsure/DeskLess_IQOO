# DeskLess: the film, the deck and the screens

What there is to look at without running anything. The same files are shown on the [home page](https://www.shaikalthaf.in/competitions/iqoo/), and the project itself is described in the [README](../README.md) one folder up.

| File | What it is |
|---|---|
| [DeskLess-film.mp4](DeskLess-film.mp4) | The film, two minutes: the problem, the four steps from a spoken sentence to a checked repair, what happens with no network, a console for every role. Its screens are drawn illustrations, and the names and numbers in it are made up. |
| [DeskLess-Pitch-Deck.pdf](DeskLess-Pitch-Deck.pdf) | The pitch deck for the finale's jury, 20 slides. The same slides as pictures are in [deck/](deck/). |
| [DeskLess-Business-Model-Canvas.pdf](DeskLess-Business-Model-Canvas.pdf) | Three sheets: the business model canvas, the revenue model and the go-to-market. The prices on them are indicative. |
| [DeskLess-Brochure.pdf](DeskLess-Brochure.pdf) | The six-page brochure that the home page's form emails. |
| [screenshots/](screenshots/) | The screens below. |

[![The DeskLess film](film-poster.jpg)](DeskLess-film.mp4)

## Where the screens come from

- **`iqoo-*`** were captured on the iQOO 15.
- **`app-*`, `user-*` and `desk-*`** were captured on the Android emulator while the app was being built. The photographs inside them are the emulator's test room, and the names and places are test data.
- **`dash-*`** show a sample company on a development server, not a real organisation.
- **`report-*`** are pages of a report the app built on the emulator.

## The app on the iQOO 15

<table>
<tr>
<td><img src="screenshots/iqoo-home.webp" width="230" alt="The first screen on the iQOO 15"></td>
<td><img src="screenshots/iqoo-ask.webp" width="230" alt="Ask DeskLess on the iQOO 15"></td>
<td><img src="screenshots/iqoo-talk.webp" width="230" alt="Walkie-talkie on the iQOO 15"></td>
</tr>
<tr>
<td>The first screen: the report type, the place, My desk, walkie-talkie, add an issue.</td>
<td>A question about the walk, answered on the phone's GPU in 2.8 seconds.</td>
<td>Walkie-talkie: hold to talk to linked phones, with no network.</td>
</tr>
</table>

## From a voice note to a report

<table>
<tr>
<td><img src="screenshots/app-record.webp" width="200" alt="Recording a voice note"></td>
<td><img src="screenshots/app-check.webp" width="200" alt="The AI check of the picture"></td>
<td><img src="screenshots/app-list.webp" width="200" alt="The issues of a walk"></td>
<td><img src="screenshots/app-home.webp" width="200" alt="The map of the walk"></td>
</tr>
<tr>
<td>A photo, then a voice note.</td>
<td>The model says what it sees in the picture and whether that matches the note.</td>
<td>The issues of the walk, grouped by type.</td>
<td>The walk drawn from the issues' own positions.</td>
</tr>
<tr>
<td><img src="screenshots/app-kept.webp" width="200" alt="A name the AI offered, kept out"></td>
<td><img src="screenshots/app-asks.webp" width="200" alt="The phone asks back"></td>
<td><img src="screenshots/app-tell.webp" width="200" alt="A spoken instruction, shown before it is done"></td>
<td><img src="screenshots/app-fix.webp" width="200" alt="Before and after a repair"></td>
</tr>
<tr>
<td>Kept out: a name and a date the model offered that nobody said.</td>
<td>The phone asks for what the note left out, and fills it from the answer.</td>
<td>An instruction becomes a list of changes, made only on <b>Do it</b>.</td>
<td>The repair photographed and compared with the first picture.</td>
</tr>
<tr>
<td><img src="screenshots/app-types.webp" width="200" alt="Report types"></td>
<td><img src="screenshots/app-language.webp" width="200" alt="Languages"></td>
<td><img src="screenshots/app-expiry.webp" width="200" alt="Expiry date read from a pack"></td>
<td><img src="screenshots/app-measured.webp" width="200" alt="Measured on the phone"></td>
</tr>
<tr>
<td>Report types for every kind of place, a company's own first.</td>
<td>The language the note is spoken in.</td>
<td>Medication audit: the expiry date read from the pack.</td>
<td>The twenty-note measurement (this run was on the emulator).</td>
</tr>
<tr>
<td><img src="screenshots/app-report.webp" width="200" alt="The finished report"></td>
<td><img src="screenshots/app-talk.webp" width="200" alt="Walkie-talkie"></td>
<td><img src="screenshots/app-ai.webp" width="200" alt="The AI screen"></td>
<td></td>
</tr>
<tr>
<td>The finished report, with <b>Save for Office Kit</b>.</td>
<td>Walkie-talkie: which linked phones are in reach, and the messages that passed.</td>
<td>The models on the phone and what they are for.</td>
<td></td>
</tr>
</table>

## For the people who use the place, and My desk

<table>
<tr>
<td><img src="screenshots/user-home.webp" width="200" alt="DeskLess User, first screen"></td>
<td><img src="screenshots/user-draft.webp" width="200" alt="An entry read back"></td>
<td><img src="screenshots/user-after.webp" width="200" alt="What became of it"></td>
</tr>
<tr>
<td>DeskLess User: choose the place, tap and say it.</td>
<td>The entry the phone made of it, to change by voice or send.</td>
<td>What became of it: repaired, checked and closed.</td>
</tr>
<tr>
<td><img src="screenshots/desk-brief.webp" width="200" alt="My desk, the briefing"></td>
<td><img src="screenshots/desk-plan.webp" width="200" alt="My desk, an instruction"></td>
<td><img src="screenshots/desk-repair.webp" width="200" alt="My desk, the repair"></td>
</tr>
<tr>
<td>My desk: what waits for this person, read aloud.</td>
<td>"Give the first one to Arjun by Monday", shown before it is done.</td>
<td>"I fixed the first one": both pictures, compared on the phone.</td>
</tr>
</table>

## The office on the web

<table>
<tr>
<td><img src="screenshots/dash-md-top.webp" width="430" alt="The managing director's console"></td>
<td><img src="screenshots/dash-admin.webp" width="430" alt="The administrator's control room"></td>
</tr>
<tr>
<td>The managing director's console: what needs them first, and the assistant's triage.</td>
<td>The administrator's control room.</td>
</tr>
<tr>
<td><img src="screenshots/dash-issue.webp" width="430" alt="An issue with the assistant's brief"></td>
<td><img src="screenshots/dash-link.webp" width="430" alt="The live linkage"></td>
</tr>
<tr>
<td>An issue with the assistant's brief, who could take it and the next step.</td>
<td>The live linkage: phones, people, places and services, and what moves between them.</td>
</tr>
<tr>
<td><img src="screenshots/dash-manager.webp" width="430" alt="The manager's console"></td>
<td><img src="screenshots/dash-tech.webp" width="215" alt="The technician's console at phone width"></td>
</tr>
<tr>
<td>The manager's console.</td>
<td>The technician's console at phone width.</td>
</tr>
</table>

The managing director's whole overview, top to bottom, is [dash-md.webp](screenshots/dash-md.webp).

## A report the app built

<table>
<tr>
<td><img src="screenshots/report-1.webp" width="430" alt="Report, page 1"></td>
<td><img src="screenshots/report-2.webp" width="430" alt="Report, page 2"></td>
</tr>
</table>

## The deck and the canvas

<table>
<tr>
<td><img src="deck/deck-01.webp" width="430" alt="Slide 1"></td>
<td><img src="deck/deck-07.webp" width="430" alt="Slide 7"></td>
</tr>
<tr>
<td><img src="deck/deck-13.webp" width="430" alt="Slide 13"></td>
<td><img src="deck/canvas.webp" width="430" alt="The business model canvas"></td>
</tr>
</table>

All 20 slides are in [deck/](deck/), and in [DeskLess-Pitch-Deck.pdf](DeskLess-Pitch-Deck.pdf).
