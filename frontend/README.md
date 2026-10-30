# Chicky frontend

Plain HTML, CSS and JavaScript, with no build step. How it should look and behave is in
[DESIGN.md](DESIGN.md).

## Run it

Start the backend first (see [backend/README.md](../backend/README.md)), then from the repo root:

```sh
node frontend/server.mjs
```

Open <http://localhost:5500>. The server also passes `/api/*` through to the backend
(`BACKEND_URL`, default `http://localhost:8080`), so the pages and the API share one address.
`FRONTEND_PORT` changes the port.

## Try it on your iPhone

The phone needs an HTTPS address that reaches this computer: iPhone Safari only allows the
microphone over HTTPS, and only shares the login cookie when the pages and API are on the same
address. [Tailscale](https://tailscale.com) gives you that privately, so only your own devices can
open it.

**Once:**

1. Install Tailscale on the computer and the iPhone (App Store) and sign in to both with the same
   account.
2. In the [Tailscale admin console](https://login.tailscale.com/admin/dns), turn on **MagicDNS**
   and **HTTPS Certificates**.

**Each time:**

1. Start the backend and `node frontend/server.mjs` as above.
2. Share the frontend with your devices:

   ```sh
   tailscale serve --bg 5500
   ```

   It prints an address like `https://your-mac.your-tailnet.ts.net`. The first time, Tailscale
   may ask you to approve HTTPS in the browser.
3. On the iPhone, turn Tailscale on, open that address in Safari and sign in with email and
   password. Google sign-in only works on the addresses registered with Google.
4. To get the app on your home screen: Share → **Add to Home Screen** → keep **Open as Web App**
   on → Add. It opens full screen with the Chicky icon. It has its own cookies, so you sign in
   once more inside it.
5. When you're done, run `tailscale serve reset`.

Don't use `tailscale funnel`: that puts the address on the public internet, and the dev backend is
connected to the real database.

## Checks

- `frontend/tests/phone-check.cjs`: signs in, records sales and gets a forecast in WebKit at
  iPhone sizes, and fails on sideways scrolling, fields that make iOS zoom in, tap targets under
  44px, or the assistant button covering a control. How to run it is at the top of the file.
- `frontend/design/capture-screenshots.cjs`: regenerates the screenshots in `DESIGN.md`.
