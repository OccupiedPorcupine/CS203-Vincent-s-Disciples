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

function showStatus(message, type = "") {
  uploadStatus.textContent = message;
  uploadStatus.className = `status-text${type ? ` is-${type}` : ""}`;
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

    row.append(name, uploaded, size, usage);
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
    else sourceSummary.textContent = "Couldn’t load your sources. Refresh the page to try again.";
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
    await apiRequest("/api/data-sources", { method: "POST", body });
    clearFile();
    showStatus("Source uploaded and available for training.", "success");
    renderSources(await apiRequest("/api/data-sources"));
  } catch (error) {
    showStatus(error.message || "Couldn’t upload the source. Try again in a moment.", "error");
    uploadButton.disabled = false;
  } finally {
    uploadButton.textContent = "Upload source";
  }
});

loadSources();
