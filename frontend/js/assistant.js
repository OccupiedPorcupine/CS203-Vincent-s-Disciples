// Voice assistant, on every signed-in page. Users say or type their sales ("today chicken rice
// 42, char siew 30"); the backend proposes entries, shown on a confirm card the user can edit.
// Nothing is saved until they press Confirm. Needs js/api.js. See backend/README.md.
(() => {
  const PAGE = location.pathname.split("/").pop().replace(/\.html$/, "") || "dashboard";
  const MAX_RECORDING_MS = 60000;
  // Stop recording after this much quiet, once the user has started speaking.
  const SILENCE_MS = 2000;
  const SPEECH_LEVEL = 0.02;
  const CONVERSATION_KEY = "chicky-assistant-conversation";

  const MIC_ICON = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V22h2v-3.08A7 7 0 0 0 19 12h-2Z"/></svg>';
  const SEND_ICON = '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M11 20V7.83l-5.59 5.58L4 12l8-8 8 8-1.41 1.41L13 7.83V20h-2Z"/></svg>';

  let status = { available: false, voice: false };
  let conversationId = readStored(CONVERSATION_KEY);
  let recorder = null;
  let voiceLanguages = null;
  let busy = false;

  const bubble = element("button", "assistant-bubble", {
    type: "button",
    "aria-label": "Open assistant",
    "aria-expanded": "false",
    "aria-controls": "assistant-panel"
  });
  bubble.innerHTML = MIC_ICON;

  const panel = element("section", "assistant-panel", {
    id: "assistant-panel",
    role: "dialog",
    "aria-labelledby": "assistant-title",
    hidden: ""
  });
  panel.innerHTML = `
    <header class="assistant-header">
      <h2 id="assistant-title">Chicky assistant</h2>
      <button class="assistant-close" type="button" aria-label="Close assistant">&times;</button>
    </header>
    <div class="assistant-log" role="log" aria-live="polite"></div>
    <p class="assistant-status" role="status" aria-live="polite"></p>
    <form class="assistant-input">
      <textarea rows="2" aria-label="Message to the assistant" placeholder="Say or type your sales, e.g. &ldquo;chicken rice 42&rdquo;"></textarea>
      <button class="assistant-mic" type="button" aria-pressed="false" aria-label="Start voice input">${MIC_ICON}</button>
      <button class="assistant-send" type="submit" aria-label="Send">${SEND_ICON}</button>
    </form>`;

  const log = panel.querySelector(".assistant-log");
  const statusLine = panel.querySelector(".assistant-status");
  const form = panel.querySelector(".assistant-input");
  const input = form.querySelector("textarea");
  const micButton = form.querySelector(".assistant-mic");
  const sendButton = form.querySelector(".assistant-send");

  init();

  async function init() {
    try {
      status = await apiRequest("/api/assistant/status");
    } catch (error) {
      // Signed out (the page redirects) or the backend is down: no assistant.
      return;
    }

    document.body.append(bubble, panel);
    addMessage("assistant", status.available
      ? "Tell me what you sold, like “today chicken rice 42, char siew 30”. I’ll show it to you before anything is saved."
      : "The assistant isn’t set up on this server yet.");
    updateControls();

    bubble.addEventListener("click", () => setOpen(panel.hidden));
    panel.querySelector(".assistant-close").addEventListener("click", () => setOpen(false));
    panel.addEventListener("keydown", event => {
      if (event.key === "Escape") setOpen(false);
    });
    form.addEventListener("submit", event => {
      event.preventDefault();
      sendFromInput(input);
    });
    input.addEventListener("keydown", event => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        sendFromInput(input);
      }
    });
    micButton.addEventListener("click", toggleRecording);

    connectDashboardChatBox();
  }

  // The dashboard's chat box is the large version of this widget: it sends through the same
  // conversation and opens the panel to show the reply.
  function connectDashboardChatBox() {
    const chatBox = document.querySelector(".chat-box");
    if (!chatBox) return;

    const chatInput = chatBox.querySelector("textarea");
    const chatSend = chatBox.querySelector(".send");
    const chatMic = chatBox.querySelector('[aria-label="Voice input"]');

    chatInput.placeholder = "Say your sales, e.g. “chicken rice 42”";
    chatInput.addEventListener("keydown", event => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        setOpen(true);
        sendFromInput(chatInput);
      }
    });

    chatSend.disabled = !status.available;
    chatSend.title = "Send to the assistant";
    chatSend.addEventListener("click", () => {
      setOpen(true);
      sendFromInput(chatInput);
    });

    chatMic.disabled = !status.voice;
    chatMic.title = status.voice ? "Speak to the assistant" : "Voice input isn’t available on this server";
    chatMic.addEventListener("click", () => {
      setOpen(true);
      toggleRecording();
    });
  }

  function setOpen(open) {
    panel.hidden = !open;
    bubble.setAttribute("aria-expanded", String(open));
    bubble.setAttribute("aria-label", open ? "Close assistant" : "Open assistant");
    if (open) {
      input.focus();
    } else {
      bubble.focus();
    }
  }

  function updateControls() {
    input.disabled = !status.available || busy;
    sendButton.disabled = !status.available || busy;
    micButton.disabled = !status.voice || (busy && !recorder);
    micButton.title = status.voice ? "" : "Voice input isn’t available on this server; please type";
  }

  function sendFromInput(source) {
    const text = source.value.trim();
    if (!text || busy || !status.available) return;
    source.value = "";
    if (source !== input) input.value = "";
    send(text);
  }

  async function send(text) {
    const inputMode = voiceLanguages ? "voice" : "text";
    const languages = voiceLanguages;
    voiceLanguages = null;

    addMessage("user", text);
    setBusy(true, "Thinking…");

    try {
      const response = await apiRequest("/api/assistant/messages", {
        method: "POST",
        body: JSON.stringify({ conversationId, text, page: PAGE, inputMode, languages })
      });
      conversationId = response.conversationId;
      writeStored(CONVERSATION_KEY, conversationId);
      addMessage("assistant", response.reply);
      if (response.proposal) addConfirmCard(response.proposal);
    } catch (error) {
      addMessage("error", errorMessage(error));
    } finally {
      setBusy(false, "");
    }
  }

  function setBusy(isBusy, message) {
    busy = isBusy;
    statusLine.textContent = message;
    updateControls();
  }

  // ---- Voice input ----

  async function toggleRecording() {
    if (recorder) {
      recorder.stop();
      return;
    }
    if (!status.voice || busy) return;

    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (error) {
      addMessage("error", "I can’t use the microphone. Allow it from the icon in the address bar, or type instead.");
      return;
    }

    const mimeType = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"].find(type => MediaRecorder.isTypeSupported(type));
    const chunks = [];
    recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    recorder.addEventListener("dataavailable", event => {
      if (event.data.size) chunks.push(event.data);
    });

    const stopIfRecording = () => {
      if (recorder?.state === "recording") recorder.stop();
    };
    const stopWatching = watchForSilence(stream, stopIfRecording);
    const timer = setTimeout(stopIfRecording, MAX_RECORDING_MS);

    recorder.addEventListener("stop", () => {
      clearTimeout(timer);
      stopWatching();
      stream.getTracks().forEach(track => track.stop());
      const type = recorder.mimeType || mimeType || "audio/webm";
      recorder = null;
      setListening(false);

      const audio = new Blob(chunks, { type });
      if (audio.size) transcribe(audio);
    });

    recorder.start();
    setListening(true);
  }

  function setListening(listening) {
    micButton.classList.toggle("listening", listening);
    micButton.setAttribute("aria-pressed", String(listening));
    micButton.setAttribute("aria-label", listening ? "Stop voice input" : "Start voice input");
    statusLine.textContent = listening ? "Listening… tap the mic again when you’re done." : "";
    updateControls();
  }

  // Calls onSilence after SILENCE_MS of quiet that follows some speech.
  function watchForSilence(stream, onSilence) {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return () => {};

    const context = new AudioContextClass();
    const analyser = context.createAnalyser();
    analyser.fftSize = 2048;
    context.createMediaStreamSource(stream).connect(analyser);
    const samples = new Float32Array(analyser.fftSize);

    let heardSpeech = false;
    let quietSince = performance.now();
    let frame = requestAnimationFrame(check);

    function check(now) {
      analyser.getFloatTimeDomainData(samples);
      const level = Math.sqrt(samples.reduce((total, sample) => total + sample * sample, 0) / samples.length);

      if (level > SPEECH_LEVEL) {
        heardSpeech = true;
        quietSince = now;
      } else if (heardSpeech && now - quietSince > SILENCE_MS) {
        onSilence();
        return;
      }
      frame = requestAnimationFrame(check);
    }

    return () => {
      cancelAnimationFrame(frame);
      context.close();
    };
  }

  async function transcribe(audio) {
    setBusy(true, "Working out what you said…");
    try {
      const extension = audio.type.includes("mp4") ? "m4a" : "webm";
      const body = new FormData();
      body.append("audio", audio, `speech.${extension}`);
      const result = await apiRequest("/api/assistant/transcribe", { method: "POST", body });

      if (!result.text.trim()) {
        addMessage("error", "I didn’t catch that. Please try again.");
        return;
      }
      input.value = result.text;
      voiceLanguages = result.languages;
      setBusy(false, "Check the text, then press Send.");
      input.focus();
      return;
    } catch (error) {
      addMessage("error", errorMessage(error));
    }
    setBusy(false, "");
  }

  // ---- Confirm card ----

  function addConfirmCard(proposal) {
    const card = element("div", "assistant-card");
    const heading = element("p", "assistant-card-title");
    heading.textContent = "Save these sales?";

    const table = element("table", "assistant-entries");
    table.innerHTML = `<thead><tr><th scope="col">Date</th><th scope="col">Dish</th><th scope="col">Portions</th><th scope="col"><span class="visually-hidden">Remove</span></th></tr></thead>`;
    const rows = element("tbody");
    table.append(rows);

    proposal.entries.forEach(entry => rows.append(entryRow(entry)));

    const actions = element("div", "assistant-card-actions");
    const cancel = element("button", "assistant-secondary", { type: "button" });
    cancel.textContent = "Cancel";
    const confirm = element("button", "assistant-primary", { type: "button" });
    confirm.textContent = "Confirm";
    actions.append(cancel, confirm);

    card.append(heading, table, actions);
    log.append(card);
    log.scrollTop = log.scrollHeight;
    confirm.focus();

    confirm.addEventListener("click", async () => {
      const entries = [...rows.querySelectorAll("tr")].map(readRow);
      if (entries.length === 0) {
        cancel.click();
        return;
      }
      setCardEnabled(card, false);
      try {
        const result = await apiRequest(`/api/assistant/proposals/${proposal.id}/confirm`, {
          method: "POST",
          body: JSON.stringify({ entries })
        });
        closeCard(card, `Saved ${result.saved.length} ${result.saved.length === 1 ? "entry" : "entries"}.`);
        window.dispatchEvent(new CustomEvent("chicky:sales-saved", { detail: result.saved }));
      } catch (error) {
        addMessage("error", errorMessage(error));
        if (error.status === 409 || error.status === 404) {
          closeCard(card, "Not saved.");
        } else {
          setCardEnabled(card, true);
        }
      }
    });

    cancel.addEventListener("click", async () => {
      setCardEnabled(card, false);
      try {
        await apiRequest(`/api/assistant/proposals/${proposal.id}/cancel`, { method: "POST" });
      } catch (error) {
        // Unsaved either way; an expired proposal can't be cancelled or saved.
      }
      closeCard(card, "Cancelled. Nothing was saved.");
    });
  }

  function entryRow(entry) {
    const row = element("tr");
    row.dataset.dishId = entry.dishId ?? "";
    row.dataset.newDishName = entry.newDishName ?? "";
    row.dataset.spokenName = entry.spokenName ?? "";

    const dateCell = element("td");
    const date = element("input", "", { type: "date", value: entry.date, max: localToday(), "aria-label": `Date for ${entry.dishName}` });
    dateCell.append(date);

    const dishCell = element("td", "assistant-dish");
    dishCell.textContent = entry.dishName;
    if (entry.newDish) {
      const tag = element("span", "assistant-tag");
      tag.textContent = "New dish";
      dishCell.append(" ", tag);
    }
    if (entry.spokenName && entry.spokenName.toLowerCase() !== entry.dishName.toLowerCase()) {
      const heard = element("span", "assistant-heard");
      heard.textContent = `heard “${entry.spokenName}”`;
      dishCell.append(heard);
    }

    const quantityCell = element("td");
    const quantity = element("input", "", { type: "number", min: "0", max: "9999", value: entry.quantity, "aria-label": `Portions of ${entry.dishName}` });
    quantityCell.append(quantity);
    if (entry.previousQuantity !== null && entry.previousQuantity !== undefined) {
      const previous = element("span", "assistant-previous");
      previous.textContent = `was ${entry.previousQuantity}`;
      quantityCell.append(previous);
    }

    const removeCell = element("td");
    const remove = element("button", "assistant-remove", { type: "button", "aria-label": `Remove ${entry.dishName}` });
    remove.innerHTML = "&times;";
    remove.addEventListener("click", () => row.remove());
    removeCell.append(remove);

    row.append(dateCell, dishCell, quantityCell, removeCell);
    return row;
  }

  function readRow(row) {
    const [date, quantity] = row.querySelectorAll("input");
    return {
      dishId: row.dataset.dishId ? Number(row.dataset.dishId) : null,
      newDishName: row.dataset.newDishName || null,
      spokenName: row.dataset.spokenName || null,
      date: date.value,
      quantity: quantity.value === "" ? null : Number(quantity.value)
    };
  }

  function setCardEnabled(card, enabled) {
    card.querySelectorAll("input, button").forEach(control => {
      control.disabled = !enabled;
    });
  }

  function closeCard(card, message) {
    setCardEnabled(card, false);
    card.querySelector(".assistant-card-actions").remove();
    card.querySelectorAll(".assistant-remove").forEach(button => button.remove());
    const note = element("p", "assistant-card-note");
    note.textContent = message;
    card.append(note);
  }

  // ---- Helpers ----

  function addMessage(kind, text) {
    const message = element("p", `assistant-message ${kind}`);
    message.textContent = text;
    log.append(message);
    log.scrollTop = log.scrollHeight;
  }

  function errorMessage(error) {
    if (error.status === 401) return "Please sign in again.";
    return error.message || "Something went wrong. Please try again.";
  }

  function element(tag, className = "", attributes = {}) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    Object.entries(attributes).forEach(([name, value]) => node.setAttribute(name, value));
    return node;
  }

  function localToday() {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  }

  // Storage can be unavailable (private windows, blocked cookies); the assistant works without it.
  function readStored(key) {
    try {
      return sessionStorage.getItem(key);
    } catch (error) {
      return null;
    }
  }

  function writeStored(key, value) {
    try {
      sessionStorage.setItem(key, value);
    } catch (error) {
      // Not remembered across pages; a new conversation starts instead.
    }
  }
})();
