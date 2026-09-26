const config = {
  apiBaseUrl: document.querySelector('meta[name="api-base-url"]').content.replace(/\/$/, ""),
  googleClientId: ""
};

const panels = {
  login: document.querySelector("#login-panel"),
  signup: document.querySelector("#signup-panel"),
  account: document.querySelector("#account-panel")
};

let csrf = null;

function showPanel(panelName) {
  Object.entries(panels).forEach(([name, panel]) => {
    panel.hidden = name !== panelName;
  });
}

function setStatus(element, message, success = false) {
  element.textContent = message;
  element.classList.toggle("is-success", success);
}

function setFormBusy(form, busy) {
  Array.from(form.elements).forEach((element) => {
    element.disabled = busy;
  });
}

async function readResponse(response) {
  const isJson = response.headers.get("content-type")?.includes("application/json");
  const body = isJson ? await response.json() : null;
  if (!response.ok) {
    const error = new Error(body?.message || `Request failed (${response.status})`);
    error.status = response.status;
    error.code = body?.code;
    throw error;
  }
  return body;
}

async function refreshCsrf() {
  const response = await fetch(`${config.apiBaseUrl}/api/csrf`, {
    credentials: "include"
  });
  csrf = await readResponse(response);
  return csrf;
}

async function apiRequest(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  const headers = new Headers(options.headers || {});

  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    if (!csrf) await refreshCsrf();
    headers.set(csrf.headerName, csrf.token);
  }
  if (options.body) headers.set("Content-Type", "application/json");

  const response = await fetch(`${config.apiBaseUrl}${path}`, {
    ...options,
    method,
    headers,
    credentials: "include"
  });
  return readResponse(response);
}

function showAccount(user) {
  document.querySelector("#account-name").textContent = user.name || "Chicky user";
  document.querySelector("#account-email").textContent = user.email;

  const picture = document.querySelector("#account-picture");
  if (user.pictureUrl) {
    picture.src = user.pictureUrl;
    picture.alt = `${user.name || "User"} profile picture`;
    picture.hidden = false;
  } else {
    picture.removeAttribute("src");
    picture.hidden = true;
  }
  showPanel("account");
}

async function restoreSession() {
  try {
    const user = await apiRequest("/api/profile");
    showAccount(user);
  } catch (error) {
    if (error.status !== 401) {
      setStatus(document.querySelector("#login-status"), "The backend could not be reached.");
    }
  }
}

document.querySelector("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const status = document.querySelector("#login-status");
  setStatus(status, "Signing in…");
  setFormBusy(form, true);

  try {
    const data = new FormData(form);
    const user = await apiRequest("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ email: data.get("email"), password: data.get("password") })
    });
    form.reset();
    showAccount(user);
  } catch (error) {
    setStatus(status, error.message);
  } finally {
    setFormBusy(form, false);
  }
});

document.querySelector("#signup-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const status = document.querySelector("#signup-status");
  const data = new FormData(form);

  if (data.get("password") !== data.get("confirmPassword")) {
    setStatus(status, "Passwords do not match.");
    return;
  }

  setStatus(status, "Creating your account…");
  setFormBusy(form, true);
  try {
    const user = await apiRequest("/api/auth/signup", {
      method: "POST",
      body: JSON.stringify({
        name: data.get("name"),
        email: data.get("email"),
        password: data.get("password")
      })
    });
    form.reset();
    showAccount(user);
  } catch (error) {
    setStatus(status, error.message);
  } finally {
    setFormBusy(form, false);
  }
});

document.querySelector("#show-signup").addEventListener("click", () => showPanel("signup"));
document.querySelector("#show-login").addEventListener("click", () => showPanel("login"));

document.querySelector("#logout-button").addEventListener("click", async () => {
  const status = document.querySelector("#account-status");
  setStatus(status, "Signing out…");
  try {
    await apiRequest("/api/auth/logout", { method: "POST" });
    csrf = null;
    await refreshCsrf();
    showPanel("login");
    setStatus(document.querySelector("#login-status"), "You have been signed out.", true);
  } catch (error) {
    setStatus(status, error.message);
  }
});

function initializeGoogleSignIn() {
  if (!config.googleClientId) {
    document.querySelector("#google-help").hidden = false;
    return;
  }

  const script = document.createElement("script");
  script.src = "https://accounts.google.com/gsi/client";
  script.async = true;
  script.onload = () => {
    google.accounts.id.initialize({
      client_id: config.googleClientId,
      callback: async ({ credential }) => {
        const status = document.querySelector("#login-status");
        setStatus(status, "Signing in with Google…");
        try {
          const user = await apiRequest("/api/auth/google", {
            method: "POST",
            body: JSON.stringify({ credential })
          });
          showAccount(user);
        } catch (error) {
          setStatus(status, error.message);
        }
      }
    });
    google.accounts.id.renderButton(document.querySelector("#google-button"), {
      type: "standard",
      theme: "outline",
      size: "large",
      shape: "rectangular",
      text: "signin_with",
      width: 258
    });
  };
  script.onerror = () => {
    setStatus(document.querySelector("#login-status"), "Google sign-in could not be loaded.");
  };
  document.head.appendChild(script);
}

async function startApplication() {
  try {
    await refreshCsrf();
    const publicConfig = await apiRequest("/api/config");
    config.googleClientId = publicConfig.googleClientId || "";
    initializeGoogleSignIn();
    await restoreSession();
  } catch {
    setStatus(document.querySelector("#login-status"), "The backend could not be reached.");
  }
}

startApplication();
