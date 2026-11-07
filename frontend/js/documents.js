// Documents page: the owner's rules and information for the assistant (issue #151). Needs js/api.js.
// documents.html#document-12 opens document 12 for editing (the assistant links its sources here).

const MAX_DOCUMENTS = 30;
const MAX_CHARACTERS = 100000;
const FILE_TYPES = [".txt", ".md", ".markdown", ".pdf", ".docx"];

const documentCount = document.getElementById("document-count");
const editorSection = document.getElementById("editor-section");
const editorHeading = document.getElementById("editor-heading");
const editorNote = document.getElementById("editor-note");
const editorMode = document.getElementById("editor-mode");
const writeForm = document.getElementById("write-form");
const titleInput = document.getElementById("document-title");
const bodyInput = document.getElementById("document-body");
const bodyCount = document.getElementById("body-count");
const writeStatus = document.getElementById("write-status");
const cancelEdit = document.getElementById("cancel-edit");
const saveButton = document.getElementById("save-document");
const uploadForm = document.getElementById("upload-form");
const dropZone = document.getElementById("drop-zone");
const fileInput = document.getElementById("document-file");
const fileLabel = document.getElementById("file-label");
const removeFile = document.getElementById("remove-file");
const uploadStatus = document.getElementById("upload-status");
const uploadButton = document.getElementById("upload-document");
const listNote = document.getElementById("list-note");
const documentList = document.getElementById("document-list");
const listStatus = document.getElementById("list-status");

const numberFormat = new Intl.NumberFormat("en-SG");
const dateFormat = new Intl.DateTimeFormat("en-SG", { day: "numeric", month: "short", year: "numeric", timeZone: "Asia/Singapore" });

let documents = [];
let editing = null; // { id, title, body } while a document is open for editing

function showStatus(element, message, type = "") {
  element.textContent = message;
  element.className = `status-text${type ? ` is-${type}` : ""}`;
}

const STATUS_TAGS = {
  INDEXED: { text: "Searchable", className: "tag is-active", title: "The assistant can answer from this document." },
  PENDING: { text: "Not searchable yet", className: "tag", title: "The assistant can’t search this document yet." },
  FAILED: { text: "Couldn’t index", className: "tag is-warning", title: "The assistant can’t search this document. Try again." }
};

function details(item) {
  const parts = [];
  if (item.source === "ASSISTANT") parts.push("From Chicky");
  else if (item.fileName) parts.push(item.fileName);
  parts.push(`Edited ${dateFormat.format(new Date(item.updatedAt))}`);
  parts.push(`${numberFormat.format(item.characters)} characters`);
  return parts.join(" · ");
}

function documentRow(item) {
  const row = document.createElement("li");
  row.className = "document-row";
  row.id = `document-${item.id}`;
  if (editing?.id === item.id) row.classList.add("is-editing");

  const name = document.createElement("div");
  name.className = "document-name";
  const title = document.createElement("strong");
  title.textContent = item.title;
  const meta = document.createElement("span");
  meta.textContent = details(item);
  name.append(title, meta);

  const status = STATUS_TAGS[item.indexStatus] || STATUS_TAGS.PENDING;
  const tag = document.createElement("span");
  tag.className = status.className;
  tag.textContent = status.text;
  tag.title = status.title;

  const actions = document.createElement("div");
  actions.className = "document-actions";
  const edit = actionButton("Edit", `Edit ${item.title}`, () => openForEditing(item.id));
  const remove = actionButton("Delete", `Delete ${item.title}`, () => confirmDelete(item, actions));
  if (item.indexStatus === "FAILED") actions.append(actionButton("Try again", `Index ${item.title} again`, (event) => reindex(item, event.currentTarget)));
  actions.append(edit, remove);

  row.append(name, tag, actions);
  return row;
}

function actionButton(text, label, onClick) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "text-button";
  button.textContent = text;
  button.setAttribute("aria-label", label);
  button.addEventListener("click", onClick);
  return button;
}

// Delete asks once, in the row itself.
function confirmDelete(item, actions) {
  const question = document.createElement("p");
  question.textContent = "Delete this document?";
  const yes = actionButton("Delete", `Yes, delete ${item.title}`, async () => {
    yes.disabled = true;
    try {
      await apiRequest(`/api/documents/${item.id}`, { method: "DELETE" });
      if (editing?.id === item.id) resetEditor();
      showStatus(listStatus, `Deleted ${item.title}.`, "success");
      await loadDocuments();
    } catch (error) {
      showStatus(listStatus, error.message || "The document could not be deleted.", "error");
      yes.disabled = false;
    }
  });
  const no = actionButton("Keep", `Keep ${item.title}`, () => renderList());
  actions.replaceChildren(question, yes, no);
  no.focus();
}

// Embeds a document that couldn't be indexed again (issue #152).
async function reindex(item, button) {
  button.disabled = true;
  button.textContent = "Trying…";
  try {
    const result = await apiRequest(`/api/documents/${item.id}/index`, { method: "POST" });
    showStatus(listStatus, result.indexStatus === "INDEXED"
      ? `The assistant can search ${item.title} now.`
      : `${item.title} still couldn’t be indexed. Try again in a few minutes.`, result.indexStatus === "INDEXED" ? "success" : "error");
    await loadDocuments();
  } catch (error) {
    showStatus(listStatus, error.message || "Try again in a moment.", "error");
    button.disabled = false;
    button.textContent = "Try again";
  }
}

function emptyState() {
  const item = document.createElement("li");
  const message = document.createElement("p");
  message.className = "empty-state";
  message.textContent = "No documents yet. Add what you’d tell a new helper, and the assistant can answer from it. For example:";
  const examples = document.createElement("ul");
  examples.className = "document-examples";
  [
    "Suppliers: who to call for chicken, eggs and rice, and when to order by.",
    "Helper rules: opening and closing routine, cash handling, hygiene.",
    "When things go wrong: the gas runs out, the fridge stops, a supplier doesn’t come."
  ].forEach((text) => {
    const example = document.createElement("li");
    example.textContent = text;
    examples.append(example);
  });
  item.append(message, examples);
  return item;
}

function renderList() {
  documentCount.textContent = `${documents.length} of ${MAX_DOCUMENTS}`;
  documentCount.className = documents.length >= MAX_DOCUMENTS ? "tag is-warning" : "tag";
  listNote.textContent = "The assistant answers from these and says which one it used.";
  documentList.replaceChildren(...(documents.length ? documents.map(documentRow) : [emptyState()]));
  updateWriteForm();
}

async function loadDocuments() {
  try {
    documents = await apiRequest("/api/documents");
    renderList();
  } catch (error) {
    if (error.status === 401) {
      window.location.href = "index.html";
      return;
    }
    listNote.textContent = "Documents could not be loaded.";
    showStatus(listStatus, error.message || "Try again in a moment.", "error");
  }
}

function setMode(mode) {
  editorMode.querySelectorAll("button").forEach((button) => button.setAttribute("aria-pressed", String(button.dataset.mode === mode)));
  writeForm.hidden = mode !== "write";
  uploadForm.hidden = mode !== "upload";
}

function updateWriteForm() {
  const length = bodyInput.value.length;
  bodyCount.textContent = `${numberFormat.format(length)} of ${numberFormat.format(MAX_CHARACTERS)} characters`;
  bodyCount.classList.toggle("is-error", length > MAX_CHARACTERS);
  const full = !editing && documents.length >= MAX_DOCUMENTS;
  const unchanged = editing && titleInput.value === editing.title && bodyInput.value === editing.body;
  saveButton.disabled = full || unchanged || !titleInput.value.trim() || !bodyInput.value.trim() || length > MAX_CHARACTERS;
  saveButton.title = full ? "You have 30 documents. Delete one to add another." : "";
  uploadButton.disabled = documents.length >= MAX_DOCUMENTS || !fileInput.files.length;
}

async function openForEditing(id) {
  try {
    const item = await apiRequest(`/api/documents/${id}`);
    editing = { id: item.id, title: item.title, body: item.body };
    titleInput.value = item.title;
    bodyInput.value = item.body;
    editorHeading.textContent = "Edit document";
    editorNote.textContent = item.source === "UPLOAD" && item.fileName
      ? `The text from ${item.fileName}. Changes here don’t change the file.`
      : "Changes are saved when you press Save changes.";
    editorMode.hidden = true;
    cancelEdit.hidden = false;
    saveButton.textContent = "Save changes";
    setMode("write");
    showStatus(writeStatus, "");
    renderList();
    editorSection.scrollIntoView({ behavior: "smooth", block: "start" });
    titleInput.focus({ preventScroll: true });
  } catch (error) {
    showStatus(listStatus, error.status === 404 ? "That document no longer exists." : (error.message || "The document could not be opened."), "error");
  }
}

function resetEditor() {
  editing = null;
  writeForm.reset();
  editorHeading.textContent = "Add a document";
  editorNote.textContent = "Write it here, or upload a .txt, .md, .pdf or .docx file of up to 5 MB.";
  editorMode.hidden = false;
  cancelEdit.hidden = true;
  saveButton.textContent = "Save document";
  if (location.hash) history.replaceState(null, "", location.pathname);
  renderList();
}

writeForm.addEventListener("input", updateWriteForm);
cancelEdit.addEventListener("click", () => {
  resetEditor();
  showStatus(writeStatus, "");
});

writeForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const label = saveButton.textContent;
  saveButton.disabled = true;
  saveButton.textContent = "Saving…";
  showStatus(writeStatus, "Saving…");
  try {
    const body = JSON.stringify({ title: titleInput.value, body: bodyInput.value });
    const saved = editing
      ? await apiRequest(`/api/documents/${editing.id}`, { method: "PUT", body })
      : await apiRequest("/api/documents", { method: "POST", body });
    const wasEditing = Boolean(editing);
    saveButton.textContent = label;
    resetEditor();
    showStatus(writeStatus, wasEditing ? `Saved changes to ${saved.title}.` : `Added ${saved.title}.`, "success");
    await loadDocuments();
  } catch (error) {
    saveButton.textContent = label;
    showStatus(writeStatus, error.message || "The document could not be saved.", "error");
    updateWriteForm();
  }
});

editorMode.addEventListener("click", (event) => {
  const button = event.target.closest("button[data-mode]");
  if (button) setMode(button.dataset.mode);
});

function setFile(file) {
  if (!file) return clearFile();
  if (!FILE_TYPES.some((type) => file.name.toLowerCase().endsWith(type))) {
    showStatus(uploadStatus, "Choose a .txt, .md, .pdf or .docx file.", "error");
    return clearFile();
  }
  if (file.size > 5 * 1024 * 1024) {
    showStatus(uploadStatus, "Files can be up to 5 MB. This one is bigger.", "error");
    return clearFile();
  }
  const transfer = new DataTransfer();
  transfer.items.add(file);
  fileInput.files = transfer.files;
  fileLabel.textContent = file.name;
  removeFile.hidden = false;
  showStatus(uploadStatus, "");
  updateWriteForm();
}

function clearFile() {
  fileInput.value = "";
  fileLabel.textContent = "Choose a file or drop it here";
  removeFile.hidden = true;
  updateWriteForm();
}

fileInput.addEventListener("change", () => setFile(fileInput.files[0]));
removeFile.addEventListener("click", () => {
  clearFile();
  showStatus(uploadStatus, "");
});
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
  showStatus(uploadStatus, `Reading ${file.name}…`);
  try {
    const form = new FormData();
    form.append("file", file);
    const saved = await apiRequest("/api/documents/upload", { method: "POST", body: form });
    clearFile();
    showStatus(uploadStatus, "");
    await loadDocuments();
    // Show the text that was read from the file, so the owner can fix anything that came out wrong.
    await openForEditing(saved.id);
    showStatus(writeStatus, `Added ${saved.title}. This is the text Chicky read from the file: fix anything that’s off, then press Save changes.`, "success");
  } catch (error) {
    showStatus(uploadStatus, error.message || "The file could not be uploaded.", "error");
  } finally {
    uploadButton.textContent = "Upload file";
    updateWriteForm();
  }
});

// documents.html#document-12 opens that document.
async function openFromAddress() {
  const match = location.hash.match(/^#document-(\d+)$/);
  if (match) await openForEditing(Number(match[1]));
}

window.addEventListener("hashchange", openFromAddress);

(async () => {
  await loadDocuments();
  await openFromAddress();
})();

// Sent by js/assistant.js when the user saves a note from the assistant (issue #154).
window.addEventListener("chicky:documents-saved", loadDocuments);
