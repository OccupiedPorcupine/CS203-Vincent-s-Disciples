// "Opening days" on the Data page: the stall's hawker centre and NEA's closures of it, weekly rest
// days, trading hours and one-off closed days.
const hawkerCentreSelect = document.getElementById("hawker-centre");
const hawkerCentreStatus = document.getElementById("hawker-centre-status");
const neaClosureList = document.getElementById("nea-closure-list");
const restDayBoxes = document.querySelectorAll('input[name="rest-day"]');
const closedDateForm = document.getElementById("closed-date-form");
const closedDateInput = document.getElementById("closed-date");
const closedReasonInput = document.getElementById("closed-reason");
const closedDateButton = document.getElementById("closed-date-button");
const closedDateList = document.getElementById("closed-date-list");
const closuresStatus = document.getElementById("closures-status");
const hoursForm = document.getElementById("trading-hours");
const opensAtInput = document.getElementById("opens-at");
const closesAtInput = document.getElementById("closes-at");
const hoursButton = document.getElementById("trading-hours-button");
const hoursStatus = document.getElementById("trading-hours-status");

// Closed days older than this aren't listed (they still count on the dashboard).
const PAST_DAYS_SHOWN = 30;

function showClosuresStatus(message, type = "", element = closuresStatus) {
  element.textContent = message;
  element.className = `status-text${type ? ` is-${type}` : ""}`;
}

function todayInSingapore() {
  return new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Singapore" });
}

function formatClosedDay(isoDate) {
  return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString("en-SG", {
    weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "UTC"
  });
}

function formatRange(startDate, endDate) {
  if (startDate === endDate) return formatClosedDay(startDate);
  const start = new Date(`${startDate}T00:00:00Z`).toLocaleDateString("en-SG", {
    weekday: "short", day: "numeric", month: "short", timeZone: "UTC"
  });
  return `${start} – ${formatClosedDay(endDate)}`;
}

function dayCount(startDate, endDate) {
  return Math.round((new Date(`${endDate}T00:00:00Z`) - new Date(`${startDate}T00:00:00Z`)) / 86400000) + 1;
}

// One row per NEA closure: open anyway when the stall trades regardless, or close it again.
function renderNeaClosures(hawkerCentre, neaClosures) {
  hawkerCentreSelect.value = hawkerCentre ? String(hawkerCentre.id) : "";
  neaClosureList.replaceChildren(...neaClosures.map(closure => {
    const item = document.createElement("li");
    const allOpen = closure.openDays.length === dayCount(closure.startDate, closure.endDate);
    item.classList.toggle("is-open", allOpen);

    const label = document.createElement("span");
    label.textContent = `${formatRange(closure.startDate, closure.endDate)} · ${closure.reason}`;
    const notes = [];
    if (closure.remarks) notes.push(closure.remarks);
    if (allOpen) notes.push("You’re opening anyway.");
    else if (closure.openDays.length) notes.push(`Opening anyway on ${closure.openDays.map(formatClosedDay).join(", ")}.`);
    if (notes.length) {
      const note = document.createElement("span");
      note.className = "nea-closure-note";
      note.textContent = notes.join(" ");
      label.append(note);
    }

    const open = closure.openDays.length === 0;
    const toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "text-button";
    toggle.textContent = open ? "Open anyway" : "Close again";
    toggle.setAttribute("aria-label", `${toggle.textContent}: ${formatRange(closure.startDate, closure.endDate)}`);
    toggle.addEventListener("click", () => saveClosures("/api/closures/nea-days", {
      method: "PUT",
      body: JSON.stringify({ from: closure.startDate, to: closure.endDate, open })
    }, open ? `You’re open ${formatRange(closure.startDate, closure.endDate)}.` : `Closed again ${formatRange(closure.startDate, closure.endDate)}.`,
    hawkerCentreStatus));

    item.append(label, toggle);
    return item;
  }));
}

function renderClosures({ restDays, closedDates, hours, hawkerCentre = null, neaClosures = [] }) {
  renderNeaClosures(hawkerCentre, neaClosures);
  restDayBoxes.forEach(box => { box.checked = restDays.includes(box.value); });
  opensAtInput.value = hours ? hours.opensAt : "";
  closesAtInput.value = hours ? hours.closesAt : "";
  updateHoursButton();

  const today = todayInSingapore();
  const cutoff = new Date(`${today}T00:00:00Z`);
  cutoff.setUTCDate(cutoff.getUTCDate() - PAST_DAYS_SHOWN);
  // NEA's days are listed with the hawker centre above.
  const shown = closedDates.filter(closed => closed.source !== "NEA" && closed.date >= cutoff.toISOString().slice(0, 10));

  closedDateList.replaceChildren(...shown.map(closed => {
    const item = document.createElement("li");
    item.classList.toggle("is-past", closed.date < today);

    const label = document.createElement("span");
    label.textContent = closed.reason
      ? `${formatClosedDay(closed.date)} · ${closed.reason}`
      : formatClosedDay(closed.date);

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "text-button";
    remove.textContent = "Remove";
    remove.setAttribute("aria-label", `Remove ${formatClosedDay(closed.date)}`);
    remove.addEventListener("click", () => reopenDate(closed.date));

    item.append(label, remove);
    return item;
  }));
}

// Messages go to the opening days status line unless the form has its own (trading hours).
async function saveClosures(path, options, success, statusElement = closuresStatus) {
  try {
    renderClosures(await apiRequest(path, options));
    showClosuresStatus(success, "success", statusElement);
    return true;
  } catch (error) {
    console.error(error);
    showClosuresStatus(error.status === 400 && error.message ? error.message : "Couldn’t save. Try again in a moment.", "error", statusElement);
    loadClosures();
    return false;
  }
}

function reopenDate(date) {
  saveClosures(`/api/closures/dates/${date}`, { method: "DELETE" }, `${formatClosedDay(date)} is open again.`);
}

restDayBoxes.forEach(box => box.addEventListener("change", () => {
  const restDays = [...restDayBoxes].filter(other => other.checked).map(other => other.value);
  saveClosures("/api/closures/rest-days", {
    method: "PUT",
    body: JSON.stringify({ restDays })
  }, "Rest days saved.");
}));

// As with the other forms, Save hours stays disabled until both times are filled in.
function updateHoursButton() {
  hoursButton.disabled = !opensAtInput.value || !closesAtInput.value;
}

opensAtInput.addEventListener("input", updateHoursButton);
closesAtInput.addEventListener("input", updateHoursButton);

hoursForm.addEventListener("submit", async event => {
  event.preventDefault();
  if (hoursButton.disabled) return;
  hoursButton.disabled = true;
  hoursButton.textContent = "Saving…";
  showClosuresStatus("", "", hoursStatus);
  await saveClosures("/api/closures/hours", {
    method: "PUT",
    body: JSON.stringify({ opensAt: opensAtInput.value, closesAt: closesAtInput.value })
  }, `Trading hours saved: ${opensAtInput.value} to ${closesAtInput.value}.`, hoursStatus);
  hoursButton.textContent = "Save hours";
  updateHoursButton();
});

// As with the other forms, Add closed day stays disabled until a date is picked.
function updateClosedDateButton() {
  closedDateButton.disabled = !closedDateInput.value;
}

closedDateInput.addEventListener("input", updateClosedDateButton);

closedDateForm.addEventListener("submit", async event => {
  event.preventDefault();
  if (closedDateButton.disabled) return;
  const date = closedDateInput.value;
  closedDateButton.disabled = true;
  closedDateButton.textContent = "Adding…";
  showClosuresStatus("");
  const saved = await saveClosures(`/api/closures/dates/${date}`, {
    method: "PUT",
    body: JSON.stringify({ reason: closedReasonInput.value })
  }, `${formatClosedDay(date)} marked as closed.`);
  closedDateButton.textContent = "Add closed day";
  // Keep what was typed if it didn't save, so it can be tried again.
  if (saved) closedDateForm.reset();
  updateClosedDateButton();
});

// NEA's list of hawker centres, to pick the stall's. Empty until the backend has fetched it once.
async function loadHawkerCentres() {
  try {
    const centres = await apiRequest("/api/closures/hawker-centres");
    const none = new Option(centres.length ? "Not in a hawker centre" : "NEA’s list isn’t available yet", "");
    hawkerCentreSelect.replaceChildren(none, ...centres.map(centre => new Option(centre.name, centre.id)));
    hawkerCentreSelect.disabled = centres.length === 0;
  } catch (error) {
    if (error.status !== 401) {
      console.error(error);
      showClosuresStatus("Couldn’t load NEA’s list of hawker centres. Refresh the page to try again.", "error", hawkerCentreStatus);
    }
  }
}

hawkerCentreSelect.addEventListener("change", () => {
  const id = hawkerCentreSelect.value ? Number(hawkerCentreSelect.value) : null;
  const name = hawkerCentreSelect.selectedOptions[0]?.textContent;
  saveClosures("/api/closures/hawker-centre", {
    method: "PUT",
    body: JSON.stringify({ hawkerCentreId: id })
  }, id ? `Saved. NEA’s closures of ${name} now count as closed days.` : "Saved. NEA’s closures no longer count.", hawkerCentreStatus);
});

async function loadClosures() {
  try {
    renderClosures(await apiRequest("/api/closures"));
  } catch (error) {
    if (error.status !== 401) {
      console.error(error);
      showClosuresStatus("Couldn’t load your opening days. Refresh the page to try again.", "error");
    }
  }
}

// The centres first, so the stall's centre can be selected when the closures arrive.
loadHawkerCentres().then(loadClosures);

// Sent by js/assistant.js when the user confirms closed days with the voice assistant.
window.addEventListener("chicky:closures-saved", loadClosures);
