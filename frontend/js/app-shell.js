// Shared header on every signed-in page: one menu open at a time, menus close on an outside
// click or Escape, and the account menu shows who is signed in and logs out. Needs js/api.js.
(() => {
  const menus = [...document.querySelectorAll(".topbar details")];

  menus.forEach(menu => menu.addEventListener("toggle", () => {
    if (menu.open) menus.filter(other => other !== menu).forEach(other => { other.open = false; });
  }));

  document.addEventListener("click", event => {
    // The guided tour opens the menu to point inside it; a click on its shade shouldn't close it.
    if (event.target.closest(".guide-layer")) return;
    menus.filter(menu => menu.open && !menu.contains(event.target)).forEach(menu => { menu.open = false; });
  });

  document.addEventListener("keydown", event => {
    const open = menus.find(menu => menu.open);
    if (event.key !== "Escape" || !open) return;
    open.open = false;
    open.querySelector("summary").focus();
  });

  const account = document.querySelector(".account-menu");
  if (!account) return;

  const status = account.querySelector(".status-text");
  const logOut = account.querySelector('[data-action="log-out"]');

  // Signed-out visitors are sent to the sign-in page by the page's own script.
  apiRequest("/api/profile")
    .then(profile => {
      account.querySelector(".account-name").textContent = profile.name || "Signed in";
      account.querySelector(".account-email").textContent = profile.email || "";
    })
    .catch(() => {});

  logOut.addEventListener("click", async () => {
    logOut.disabled = true;
    logOut.textContent = "Logging out…";
    status.textContent = "";
    try {
      await apiRequest("/api/auth/logout", { method: "POST" });
      window.location.href = "index.html";
    } catch (error) {
      logOut.disabled = false;
      logOut.textContent = "Log out";
      status.textContent = "Couldn’t log out. Try again.";
      status.classList.add("is-error");
    }
  });
})();
