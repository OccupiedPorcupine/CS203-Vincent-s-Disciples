// Locally the pages (port 5500) call the backend at the address in the api-base-url meta tag.
// When deployed, the backend is served under /api on the same address as the pages.
const API_BASE_URL = ["localhost", "127.0.0.1"].includes(location.hostname)
    ? document.querySelector('meta[name="api-base-url"]').content
    : "";

async function getCsrf() {
    const response = await fetch(`${API_BASE_URL}/api/csrf`, {
        credentials: "include"
    });

    if (!response.ok) {
        throw new Error(`Failed to get CSRF token: ${response.status}`);
    }

    return response.json();
}

async function apiRequest(path, options = {}) {
    const method = options.method || "GET";

    const requestOptions = {
        ...options,
        method,
        credentials: "include",
        headers: {
            ...(options.headers || {})
        }
    };

    if (["POST", "PUT", "PATCH", "DELETE"].includes(method.toUpperCase())) {
        const csrf = await getCsrf();
        requestOptions.headers[csrf.headerName] = csrf.token;
    }

    if (options.body && !(options.body instanceof FormData)) {
        requestOptions.headers["Content-Type"] = "application/json";
    }

    const response = await fetch(`${API_BASE_URL}${path}`, requestOptions);

    if (!response.ok) {
        const contentType = response.headers.get("content-type") || "";
        const body = contentType.includes("application/json")
            ? await response.json()
            : await response.text();
        const error = new Error(body?.message || body || `Request failed: ${response.status}`);
        error.status = response.status;
        error.code = body?.code;
        throw error;
    }

    // e.g. the assistant's spoken replies (audio).
    if (options.responseType === "blob") {
        return response.blob();
    }

    // 204 No Content, e.g. cancelling an assistant proposal.
    if (response.status === 204) {
        return null;
    }

    return response.json();
}
