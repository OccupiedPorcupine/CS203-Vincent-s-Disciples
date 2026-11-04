// Account page: who is signed in, and linking chat apps such as Telegram (issue #99). Needs
// js/api.js; the QR code needs qrcode-generator, and the page works without it.

const chatList = document.getElementById("chat-list");
const chatStatus = document.getElementById("chat-status");
const time = new Intl.DateTimeFormat("en-SG", { hour: "numeric", minute: "2-digit" });
const day = new Intl.DateTimeFormat("en-SG", { day: "numeric", month: "short", year: "numeric" });
// While a code is showing, check every few seconds whether the phone has used it.
const POLL_MS = 3000;
let polling = null;

function showStatus(message, type) {
  chatStatus.textContent = message;
  chatStatus.className = `status-text${type ? ` is-${type}` : ""}`;
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

async function loadProfile() {
  try {
    const profile = await apiRequest("/api/profile");
    document.getElementById("details-name").textContent = profile.name || "Stall owner";
    document.getElementById("details-email").textContent = profile.email || "";
  } catch (error) {
    if (error.status === 401) window.location.href = "index.html";
  }
}

async function loadChats() {
  try {
    renderChats((await apiRequest("/api/chat-links")).channels);
  } catch (error) {
    if (error.status === 401) window.location.href = "index.html";
    else showStatus("Chat apps could not be loaded.", "error");
  }
}

function renderChats(channels) {
  chatList.replaceChildren();
  if (!channels.length) {
    chatList.append(element("li", "empty-state", "No chat apps are set up on this server yet."));
    return;
  }
  channels.forEach(channel => chatList.append(chatRow(channel)));
}

function chatRow(channel) {
  const row = element("li", "chat-app");
  const text = element("div", "chat-app-text");
  text.append(element("p", "chat-app-name", channel.label));
  const linked = channel.linked;
  text.append(element("p", "muted-text", linked
    ? `Linked${linked.displayName ? ` to ${linked.displayName}` : ""} since ${day.format(new Date(linked.linkedAt))}`
    : "Not linked"));

  const button = element("button", linked ? "secondary-button" : "primary-button", linked ? "Disconnect" : "Connect");
  button.type = "button";
  const connect = element("div", "chat-connect");
  connect.hidden = true;
  button.addEventListener("click", () => linked ? disconnect(channel, button) : startLink(channel, button, connect));

  row.append(text, button, connect);
  return row;
}

async function startLink(channel, button, connect) {
  button.disabled = true;
  showStatus("");
  try {
    const code = await apiRequest(`/api/chat-links/${channel.channel.toLowerCase()}`, { method: "POST" });
    showCode(channel, code, connect);
    button.hidden = true;
    waitForLink(channel.channel, new Date(code.expiresAt));
  } catch (error) {
    button.disabled = false;
    showStatus(error.message || `Couldn’t connect ${channel.label}. Try again.`, "error");
  }
}

// The link opens the chat app with the code. On a computer, a QR code lets the phone open it.
function showCode(channel, code, connect) {
  const open = element("a", "primary-button", `Open ${channel.label}`);
  open.href = code.link;
  open.target = "_blank";
  open.rel = "noopener";
  const start = channel.channel === "TELEGRAM" ? "tap Start" : "send the message it opens with";
  // css/account.css hides the QR code at this width.
  const onPhone = window.matchMedia("(max-width: 760px)").matches;
  const help = element("p", "muted-text", (onPhone
    ? `Tap Open ${channel.label}, then ${start}.`
    : `On your phone, scan the code (or open the link), then ${start}.`)
    + ` It works until ${time.format(new Date(code.expiresAt))}.`);
  connect.replaceChildren(open, help);

  if (window.qrcode) {
    const qr = window.qrcode(0, "M");
    qr.addData(code.link);
    qr.make();
    const picture = element("div", "chat-qr");
    picture.setAttribute("role", "img");
    picture.setAttribute("aria-label", `QR code that opens ${channel.label}`);
    picture.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
    connect.append(picture);
  }
  connect.hidden = false;
}

function waitForLink(channelName, expiresAt) {
  clearInterval(polling);
  polling = setInterval(async () => {
    if (new Date() > expiresAt) {
      clearInterval(polling);
      showStatus("The code has expired. Tap Connect for a new one.");
      loadChats();
      return;
    }
    try {
      const { channels } = await apiRequest("/api/chat-links");
      const channel = channels.find(candidate => candidate.channel === channelName);
      if (channel?.linked) {
        clearInterval(polling);
        renderChats(channels);
        showStatus(`${channel.label} is linked. Send it your sales any time.`, "success");
      }
    } catch (error) {
      // Try again on the next tick.
    }
  }, POLL_MS);
}

async function disconnect(channel, button) {
  if (!window.confirm(`Disconnect ${channel.label}? That chat won’t be able to record sales any more.`)) return;
  button.disabled = true;
  try {
    await apiRequest(`/api/chat-links/${channel.linked.id}`, { method: "DELETE" });
    showStatus(`${channel.label} is disconnected.`, "success");
    loadChats();
  } catch (error) {
    button.disabled = false;
    showStatus(error.message || "Couldn’t disconnect. Try again.", "error");
  }
}

loadProfile();
loadChats();
