const API_BASE_URL = document.querySelector('meta[name="api-base-url"]').content;

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

    if (options.body) {
        requestOptions.headers["Content-Type"] = "application/json";
    }

    const response = await fetch(`${API_BASE_URL}${path}`, requestOptions);

    if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `Request failed: ${response.status}`);
    }

    return response.json();
}