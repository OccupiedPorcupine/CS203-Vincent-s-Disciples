const fileInput = document.getElementById("source-file");
const fileLabel = document.getElementById("file-label");
const dropZone = document.getElementById("drop-zone");
const uploadForm = document.getElementById("upload-form");
const uploadButton = document.getElementById("upload-button");
const removeButton = document.getElementById("remove-file");
const uploadStatus = document.getElementById("upload-status");
const sourceList = document.getElementById("source-list");
const sourceSummary = document.getElementById("source-summary");
const activeCount = document.getElementById("active-count");
const uploadSection = document.getElementById("upload-section");
const importSection = document.getElementById("import-section");
const importHeading = document.getElementById("import-heading");
const importSummary = document.getElementById("import-summary");
const importErrors = document.getElementById("import-errors");
const importDishes = document.getElementById("import-dishes");
const importDishList = document.getElementById("import-dish-list");
const importExisting = document.getElementById("import-existing");
const importExistingLabel = document.getElementById("import-existing-label");
const importButton = document.getElementById("import-button");
const importCancel = document.getElementById("import-cancel");
const importStatus = document.getElementById("import-status");

let importing = null; // { sourceId, preview } while the import panel is open

function setFile(file) {
  if (!file) return clearFile();
  const extension = file.name.toLowerCase();
  if (!extension.endsWith(".csv") && !extension.endsWith(".xlsx")) {
    showStatus("Choose a CSV or XLSX file.", "error");
    return clearFile();
  }
  const transfer = new DataTransfer();
  transfer.items.add(file);
  fileInput.files = transfer.files;
  fileLabel.textContent = file.name;
  uploadButton.disabled = false;
  removeButton.hidden = false;
  showStatus("");
}

function clearFile() {
  fileInput.value = "";
  fileLabel.textContent = "Choose a file or drop it here";
  uploadButton.disabled = true;
  removeButton.hidden = true;
}

function showStatus(message, type = "", element = uploadStatus) {
  element.textContent = message;
  element.className = `status-text${type ? ` is-${type}` : ""}`;
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function renderSources(sources) {
  sourceList.replaceChildren();
  const active = sources.filter((source) => source.usedByModel).length;
  activeCount.textContent = `${active} active`;
  sourceSummary.textContent = sources.length
    ? `${sources.length} source${sources.length === 1 ? "" : "s"} registered · ${active} used by the active model`
    : "No training sources uploaded yet";

  if (!sources.length) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "Upload the first data source to make it available for training.";
    sourceList.append(empty);
    return;
  }

  sources.forEach((source) => {
    const row = document.createElement("div");
    row.className = "source-row";
    row.setAttribute("role", "row");

    const name = document.createElement("div");
    name.className = "source-name";
    const strong = document.createElement("strong");
    strong.textContent = source.filename;
    const hash = document.createElement("span");
    hash.textContent = `SHA-256 ${source.sha256.slice(0, 12)}…`;
    name.append(strong, hash);

    const uploaded = document.createElement("span");
    uploaded.textContent = new Date(source.uploadedAt).toLocaleString("en-SG", { dateStyle: "medium", timeStyle: "short" });
    const size = document.createElement("span");
    size.textContent = formatBytes(source.sizeBytes);
    const usage = document.createElement("span");
    usage.className = `tag${source.usedByModel ? " is-active" : ""}`;
    usage.textContent = source.usedByModel ? "Used by active model" : "Available for training";

    const sales = document.createElement("div");
    sales.className = "source-import";
    const importAction = document.createElement("button");
    importAction.type = "button";
    importAction.className = "secondary-button";
    importAction.textContent = source.lastImport ? "Import again" : "Import sales";
    importAction.setAttribute("aria-label", `${importAction.textContent} from ${source.filename}`);
    importAction.addEventListener("click", () => openImport(source.id));
    sales.append(importAction);
    if (source.lastImport) {
      const last = document.createElement("span");
      last.textContent = `Imported ${formatDate(source.lastImport.at)}: ${describeImport(source.lastImport)}`;
      sales.append(last);
    }

    row.append(name, uploaded, size, usage, sales);
    sourceList.append(row);
  });
}

async function loadSources() {
  try {
    await apiRequest("/api/profile");
    const sources = await apiRequest("/api/data-sources");
    renderSources(sources);
  } catch (error) {
    if (error.status === 401) window.location.href = "index.html";
    else sourceSummary.textContent = "Sources could not be loaded.";
  }
}

fileInput.addEventListener("change", () => setFile(fileInput.files[0]));
removeButton.addEventListener("click", clearFile);
dropZone.addEventListener("dragover", (event) => { event.preventDefault(); dropZone.classList.add("dragging"); });
dropZone.addEventListener("dragleave", () => dropZone.classList.remove("dragging"));
dropZone.addEventListener("drop", (event) => {
  event.preventDefault();
  dropZone.classList.remove("dragging");
  setFile(event.dataTransfer.files[0]);
});

uploadForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const file = fileInput.files[0];
  if (!file) return;
  uploadButton.disabled = true;
  uploadButton.textContent = "Uploading…";
  showStatus("Checking and storing the source…");
  const body = new FormData();
  body.append("file", file);

  try {
    const source = await apiRequest("/api/data-sources", { method: "POST", body });
    clearFile();
    showStatus("Source uploaded and available for training.", "success");
    renderSources(await apiRequest("/api/data-sources"));
    openImport(source.id);
  } catch (error) {
    showStatus(error.message || "The source could not be uploaded.", "error");
    uploadButton.disabled = false;
  } finally {
    uploadButton.textContent = "Upload source";
  }
});

// ---- Importing a file into daily sales ----

const NEW_DISH = "new";
const LEAVE_OUT = "leave-out";

const money = new Intl.NumberFormat("en-SG", { style: "currency", currency: "SGD", currencyDisplay: "narrowSymbol" });

function plural(count, word, words = `${word}s`) {
  return `${count.toLocaleString("en-SG")} ${count === 1 ? word : words}`;
}

// "600 days added, 12 replaced" or "nothing new: 600 days already recorded".
function describeImport({ rowsAdded, rowsOverwritten, rowsSkipped }) {
  if (!rowsAdded && !rowsOverwritten) return `nothing new, ${plural(rowsSkipped, "day")} already recorded`;
  const parts = [`${plural(rowsAdded, "day")} added`];
  if (rowsOverwritten) parts.push(`${rowsOverwritten.toLocaleString("en-SG")} replaced`);
  if (rowsSkipped) parts.push(`${rowsSkipped.toLocaleString("en-SG")} already recorded`);
  return parts.join(", ");
}

function formatDate(value) {
  const date = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00`) : new Date(value);
  return date.toLocaleDateString("en-SG", { day: "numeric", month: "short", year: "numeric" });
}

async function openImport(sourceId) {
  importing = { sourceId, preview: null };
  uploadSection.hidden = true;
  importSection.hidden = false;
  importHeading.textContent = "Import sales";
  importSummary.textContent = "Reading the file…";
  importErrors.hidden = true;
  importErrors.replaceChildren();
  importDishes.hidden = true;
  importDishList.replaceChildren();
  importExisting.hidden = true;
  importButton.hidden = true;
  showStatus("", "", importStatus);
  importSection.scrollIntoView({ behavior: "smooth", block: "start" });

  try {
    const [preview, dishes] = await Promise.all([
      apiRequest(`/api/data-sources/${sourceId}/import-preview`),
      apiRequest("/api/dishes")
    ]);
    if (importing?.sourceId !== sourceId) return;
    importing.preview = preview;
    renderPreview(preview, dishes);
  } catch (error) {
    importSummary.textContent = "";
    showStatus(error.message || "The file could not be read.", "error", importStatus);
  }
}

function renderPreview(preview, dishes) {
  importHeading.textContent = `Import sales from ${preview.filename}`;

  if (preview.errorCount > 0) {
    if (preview.errors[0].row == null) {
      // The whole file, e.g. no date, dish or quantity column.
      importSummary.textContent = preview.errors[0].message;
      return;
    }
    importSummary.textContent = `${plural(preview.errorCount, "row")} ${preview.errorCount === 1 ? "has a problem" : "have problems"}. `
      + "Fix the file, upload it again and import that.";
    importErrors.replaceChildren(...preview.errors.map(error => {
      const item = document.createElement("li");
      item.textContent = error.row ? `Row ${error.row}: ${error.message}` : error.message;
      return item;
    }));
    if (preview.errorCount > preview.errors.length) {
      const more = document.createElement("li");
      more.textContent = `…and ${plural(preview.errorCount - preview.errors.length, "more row")}.`;
      importErrors.append(more);
    }
    importErrors.hidden = false;
    return;
  }
  if (!preview.rows) {
    importSummary.textContent = "The file has no sales rows.";
    return;
  }

  importSummary.textContent = [
    plural(preview.rows, "row"),
    `${formatDate(preview.firstDate)} to ${formatDate(preview.lastDate)}`,
    plural(preview.dishes.length, "dish name"),
    preview.sheet ? `sheet “${preview.sheet}”` : null
  ].filter(Boolean).join(" · ");

  importDishList.replaceChildren(...preview.dishes.map(dish => dishRow(dish, dishes)));
  importDishes.hidden = false;

  if (preview.rowsWithExistingSales > 0) {
    importExistingLabel.textContent = `${plural(preview.rowsWithExistingSales, "day")} in this file already ${preview.rowsWithExistingSales === 1 ? "has" : "have"} sales recorded.`;
    importExisting.querySelector('input[value="SKIP"]').checked = true;
    importExisting.hidden = false;
  }
  importButton.textContent = `Import ${plural(preview.rows, "row")}`;
  importButton.disabled = false;
  importButton.hidden = false;
}

function dishRow(dish, dishes) {
  const row = document.createElement("div");
  row.className = "import-row";
  row.setAttribute("role", "row");
  row.dataset.name = dish.name;

  const name = document.createElement("div");
  name.className = "source-name";
  const strong = document.createElement("strong");
  strong.textContent = dish.name;
  const note = document.createElement("span");
  const filePrice = dish.price === null ? "" : ` · ${money.format(Number(dish.price))} in the file`;
  note.textContent = (dish.match?.by === "nickname" ? `Nickname for ${dish.match.dishName}` : `${dish.portions.toLocaleString("en-SG")} portions`) + filePrice;
  name.append(strong, note);

  const days = document.createElement("span");
  days.textContent = dish.rows.toLocaleString("en-SG");

  const choice = document.createElement("div");
  choice.className = "import-choice";
  const selectWrap = document.createElement("div");
  selectWrap.className = "select-wrap";
  const select = document.createElement("select");
  select.className = "input";
  select.setAttribute("aria-label", `Record “${dish.name}” as`);
  selectWrap.append(select);
  select.append(new Option("Add as a new dish", NEW_DISH));
  dishes.forEach(existing => select.append(new Option(existing.name, String(existing.id))));
  select.append(new Option("Leave out", LEAVE_OUT));
  select.value = dish.match ? String(dish.match.dishId) : NEW_DISH;
  const tag = document.createElement("span");
  tag.className = "tag is-warning";
  tag.textContent = "New dish";
  const updateTag = () => { tag.hidden = select.value !== NEW_DISH; };
  select.addEventListener("change", updateTag);
  updateTag();
  choice.append(selectWrap, tag);

  row.append(name, days, choice);
  return row;
}

function closeImport() {
  importing = null;
  importSection.hidden = true;
  uploadSection.hidden = false;
}

async function runImport() {
  if (!importing?.preview) return;
  const { sourceId } = importing;
  const dishes = [...importDishList.querySelectorAll(".import-row")].map(row => {
    const value = row.querySelector("select").value;
    if (value === NEW_DISH) return { name: row.dataset.name, action: "NEW" };
    if (value === LEAVE_OUT) return { name: row.dataset.name, action: "LEAVE_OUT" };
    return { name: row.dataset.name, action: "EXISTING", dishId: Number(value) };
  });
  const existingSales = importExisting.querySelector("input:checked").value;

  importButton.disabled = true;
  importButton.textContent = "Importing…";
  showStatus("", "", importStatus);
  try {
    const result = await apiRequest(`/api/data-sources/${sourceId}/import`, {
      method: "POST",
      body: JSON.stringify({ existingSales, dishes })
    });
    closeImport();
    const created = result.createdDishes.length
      ? ` Added ${plural(result.createdDishes.length, "new dish", "new dishes")}.` : "";
    const priced = result.pricesSet ? ` Prices from the file set for ${plural(result.pricesSet, "dish", "dishes")}; see Dishes.` : "";
    const changed = result.rowsAdded || result.rowsOverwritten
      ? " The dashboard and forecasts use them now." : "";
    showStatus(`Imported: ${describeImport(result)}.${created}${priced}${changed}`, "success");
    renderSources(await apiRequest("/api/data-sources"));
  } catch (error) {
    showStatus(error.message || "The sales could not be imported.", "error", importStatus);
    importButton.disabled = false;
    importButton.textContent = `Import ${plural(importing.preview.rows, "row")}`;
  }
}

importButton.addEventListener("click", runImport);
importCancel.addEventListener("click", () => {
  closeImport();
  showStatus("");
});

loadSources();
