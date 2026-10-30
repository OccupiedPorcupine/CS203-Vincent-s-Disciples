// "Opening days" on the Data page: weekly rest days and one-off closed days.
const restDayBoxes = document.querySelectorAll('input[name="rest-day"]');
const closedDateForm = document.getElementById("closed-date-form");
const closedDateInput = document.getElementById("closed-date");
const closedReasonInput = document.getElementById("closed-reason");
const closedDateList = document.getElementById("closed-date-list");
const closuresStatus = document.getElementById("closures-status");

// Closed days older than this aren't listed (they still count on the dashboard).
const PAST_DAYS_SHOWN = 30;

function showClosuresStatus(message, type = "") {
  closuresStatus.textContent = message;
  closuresStatus.className = `status-text${type ? ` is-${type}` : ""}`;
}

function todayInSingapore() {
  return new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Singapore" });
}

function formatClosedDay(isoDate) {
  return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString("en-SG", {
    weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "UTC"
  });
}

function renderClosures({ restDays, closedDates }) {
  restDayBoxes.forEach(box => { box.checked = restDays.includes(box.value); });

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

async function saveClosures(path, options, success) {
  try {
    renderClosures(await apiRequest(path, options));
    showClosuresStatus(success, "success");
  } catch (error) {
    console.error(error);
    showClosuresStatus(error.status === 400 && error.message ? error.message : "Couldn’t save. Try again in a moment.", "error");
    loadClosures();
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

closedDateForm.addEventListener("submit", event => {
  event.preventDefault();
  const date = closedDateInput.value;
  saveClosures(`/api/closures/dates/${date}`, {
    method: "PUT",
    body: JSON.stringify({ reason: closedReasonInput.value })
  }, `${formatClosedDay(date)} marked as closed.`);
  closedDateForm.reset();
});

async function loadClosures() {
  try {
    renderClosures(await apiRequest("/api/closures"));
  } catch (error) {
    if (error.status !== 401) {
      console.error(error);
      showClosuresStatus("Couldn’t load your opening days.", "error");
    }
  }
}

loadClosures();

// Sent by js/assistant.js when the user confirms closed days with the voice assistant.
window.addEventListener("chicky:closures-saved", loadClosures);
