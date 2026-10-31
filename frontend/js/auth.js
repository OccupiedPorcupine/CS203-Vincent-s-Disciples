const config = {
  // Locally the backend runs at the api-base-url meta tag's address; when deployed it is
  // served under /api on the same address as this page (see js/api.js).
  apiBaseUrl: ["localhost", "127.0.0.1"].includes(location.hostname)
    ? document.querySelector('meta[name="api-base-url"]').content.replace(/\/$/, "")
    : "",
  googleClientId: ""
};

const panels = {
  login: document.querySelector("#login-panel"),
  signup: document.querySelector("#signup-panel"),
  forgot: document.querySelector("#forgot-panel"),
  reset: document.querySelector("#reset-panel"),
  verify: document.querySelector("#verify-panel"),
  account: document.querySelector("#account-panel")
};

// Token from an emailed link: index.html#reset=… or #verify=…. It is in the fragment so it is
// never sent to a server or written to a log. Read once, then removed from the address bar.
const emailedLink = (() => {
  const match = location.hash.match(/^#(reset|verify)=([A-Za-z0-9_-]+)$/);
  if (match) history.replaceState(null, "", location.pathname + location.search);
  return match ? { kind: match[1], token: match[2] } : null;
})();

// A link opened in a tab that already shows this page only changes the fragment; start over so
// it is handled like a fresh visit.
window.addEventListener("hashchange", () => {
  if (/^#(reset|verify)=/.test(location.hash)) location.reload();
});

let csrf = null;

function showPanel(panelName) {
  Object.entries(panels).forEach(([name, panel]) => {
    panel.hidden = name !== panelName;
  });
}

// tone: "" for progress, "success" or "error".
function setStatus(element, message, tone = "") {
  element.textContent = message;
  element.classList.toggle("is-success", tone === "success");
  element.classList.toggle("is-error", tone === "error");
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

  window.location.href = "dashboard.html";

}

async function restoreSession() {
  try {
    const user = await apiRequest("/api/profile");
    showAccount(user);
  } catch (error) {
    if (error.status !== 401) {
      setStatus(document.querySelector("#login-status"), "The backend could not be reached.", "error");
    }
  }
}

document.querySelector("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const status = document.querySelector("#login-status");
  const data = new FormData(form);
  setStatus(status, "Signing in…");
  setFormBusy(form, true);
  try {
    const user = await apiRequest("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        email: data.get("email"),
        password: data.get("password")
      })
    });

    form.reset();
    showAccount(user);
  } catch (error) {
    setStatus(status, error.message, "error");
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
    setStatus(status, "Passwords do not match.", "error");
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
    setStatus(status, error.message, "error");
  } finally {
    setFormBusy(form, false);
  }
});

document.querySelector("#show-signup").addEventListener("click", () => showPanel("signup"));
document.querySelector("#show-login").addEventListener("click", () => showPanel("login"));
document.querySelector("#show-forgot").addEventListener("click", () => {
  document.querySelector("#forgot-email").value = document.querySelector("#email").value;
  showPanel("forgot");
});
document.querySelectorAll("[data-show]").forEach((button) => {
  button.addEventListener("click", () => showPanel(button.dataset.show));
});

document.querySelector("#forgot-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const status = document.querySelector("#forgot-status");
  const email = new FormData(form).get("email");
  setStatus(status, "Sending…");
  setFormBusy(form, true);
  try {
    await apiRequest("/api/auth/password-reset", {
      method: "POST",
      body: JSON.stringify({ email })
    });
    setStatus(status, "If an account uses that email, a reset link is on its way. It works for 30 minutes.", "success");
  } catch (error) {
    setStatus(status, error.message, "error");
  } finally {
    setFormBusy(form, false);
  }
});

document.querySelector("#reset-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const status = document.querySelector("#reset-status");
  const data = new FormData(form);
  if (data.get("password") !== data.get("confirmPassword")) {
    setStatus(status, "Passwords do not match.", "error");
    return;
  }

  setStatus(status, "Changing your password…");
  setFormBusy(form, true);
  try {
    await apiRequest("/api/auth/password-reset/confirm", {
      method: "POST",
      body: JSON.stringify({ token: emailedLink.token, password: data.get("password") })
    });
    form.reset();
    showPanel("login");
    setStatus(document.querySelector("#login-status"), "Password changed. Log in with your new password.", "success");
  } catch (error) {
    setStatus(status, error.message, "error");
  } finally {
    setFormBusy(form, false);
  }
});

async function confirmEmail(token) {
  showPanel("verify");
  const status = document.querySelector("#verify-status");
  try {
    await apiRequest("/api/auth/verify-email", { method: "POST", body: JSON.stringify({ token }) });
    setStatus(status, "Thanks, your email is confirmed.", "success");
  } catch (error) {
    setStatus(status, error.message, "error");
  }
}

document.querySelector("#logout-button").addEventListener("click", async () => {
  const status = document.querySelector("#account-status");
  setStatus(status, "Signing out…");
  try {
    await apiRequest("/api/auth/logout", { method: "POST" });
    csrf = null;
    await refreshCsrf();
    showPanel("login");
    setStatus(document.querySelector("#login-status"), "You have been signed out.", "success");
  } catch (error) {
    setStatus(status, error.message, "error");
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
          setStatus(status, error.message, "error");
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
    setStatus(document.querySelector("#login-status"), "Google sign-in could not be loaded.", "error");
  };
  document.head.appendChild(script);
}

async function startApplication() {
  try {
    await refreshCsrf();
    const publicConfig = await apiRequest("/api/config");
    config.googleClientId = publicConfig.googleClientId || "";
    initializeGoogleSignIn();
    if (emailedLink?.kind === "reset") {
      showPanel("reset");
      return;
    }
    if (emailedLink?.kind === "verify") {
      await confirmEmail(emailedLink.token);
      return;
    }
    await restoreSession();
  } catch {
    setStatus(document.querySelector("#login-status"), "The backend could not be reached.", "error");
  }
}

startApplication();
