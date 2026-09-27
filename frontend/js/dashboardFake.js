async function requireLogin() {
  try {
    await apiRequest("/api/profile");
  } catch (error) {
    if (error.status === 401) {
      window.location.href = "index.html";
      return;
    }

    throw error;
  }
}

requireLogin();