// "Opening days" on the Data page: weekly rest days, trading hours and one-off closed days.
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

function renderClosures({ restDays, closedDates, hours }) {
  restDayBoxes.forEach(box => { box.checked = restDays.includes(box.value); });
  opensAtInput.value = hours ? hours.opensAt : "";
  closesAtInput.value = hours ? hours.closesAt : "";
  updateHoursButton();

  const today = todayInSingapore();
  const cutoff = new Date(`${today}T00:00:00Z`);
  cutoff.setUTCDate(cutoff.getUTCDate() - PAST_DAYS_SHOWN);
  const shown = closedDates.filter(closed => closed.date >= cutoff.toISOString().slice(0, 10));

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

loadClosures();

// Sent by js/assistant.js when the user confirms closed days with the voice assistant.
window.addEventListener("chicky:closures-saved", loadClosures);
