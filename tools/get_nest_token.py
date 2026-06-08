#!/usr/bin/env python3
"""
One-time desktop OAuth to get a Nest SDM refresh token.

Why desktop: the Portal has no usable browser for an in-app OAuth consent flow,
so we do the consent once here and bake the long-lived refresh token into the app.

Prereqs (do these in the Google consoles first):
  1. Enable Device Access + create a project: https://console.nest.google.com/device-access
     (one-time $5 fee). Note the PROJECT ID.
  2. In Google Cloud console, enable the "Smart Device Management API".
  3. Create an OAuth client (type: Web application) and add this redirect URI:
        http://localhost:8080/
     Note the CLIENT ID and CLIENT SECRET.
  4. Link your Google account to the project (Device Access console gives you an
     authorization URL the first time you authorize below).

Usage:
  python3 get_nest_token.py --client-id XXX --client-secret YYY --project-id ZZZ

It prints client_id / client_secret / refresh_token / project_id ready to paste
into the app's Settings screen or app/src/main/assets/config.json.
"""
import argparse
import http.server
import json
import urllib.parse
import urllib.request
import webbrowser

REDIRECT = "http://localhost:8080/"
SCOPE = "https://www.googleapis.com/auth/sdm.service"
AUTH = "https://nestservices.google.com/partnerconnections"
TOKEN = "https://oauth2.googleapis.com/token"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--client-id", required=True)
    ap.add_argument("--client-secret", required=True)
    ap.add_argument("--project-id", required=True, help="Device Access project ID")
    args = ap.parse_args()

    # The SDM consent URL is project-scoped.
    params = {
        "redirect_uri": REDIRECT,
        "access_type": "offline",
        "prompt": "consent",
        "client_id": args.client_id,
        "response_type": "code",
        "scope": SCOPE,
    }
    auth_url = f"{AUTH}/{args.project_id}/auth?" + urllib.parse.urlencode(params)
    print("\nOpen this URL, approve access, and you'll be redirected to localhost:\n")
    print(auth_url, "\n")
    webbrowser.open(auth_url)

    code_holder = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.urlparse(self.path).query
            code_holder["code"] = urllib.parse.parse_qs(q).get("code", [None])[0]
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"Done. You can close this tab and return to the terminal.")

        def log_message(self, *a):  # silence
            pass

    print("Waiting for the redirect on http://localhost:8080/ ...")
    http.server.HTTPServer(("localhost", 8080), Handler).handle_request()

    code = code_holder.get("code")
    if not code:
        raise SystemExit("No authorization code received.")

    data = urllib.parse.urlencode({
        "client_id": args.client_id,
        "client_secret": args.client_secret,
        "code": code,
        "grant_type": "authorization_code",
        "redirect_uri": REDIRECT,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN, data=data)) as r:
        tok = json.load(r)

    refresh = tok.get("refresh_token")
    if not refresh:
        raise SystemExit(f"No refresh_token in response: {tok}")

    print("\n=== Paste these into the app ===")
    print(json.dumps({
        "project_id": args.project_id,
        "client_id": args.client_id,
        "client_secret": args.client_secret,
        "refresh_token": refresh,
    }, indent=2))


if __name__ == "__main__":
    main()
