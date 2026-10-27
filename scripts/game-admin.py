#!/usr/bin/env python3
"""Administrative HTTP client. Credentials and exported logs are never sent to another host."""
import argparse
import getpass
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http(url, token=None, body=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with build_opener(NoRedirects).open(request, timeout=60) as response:
            return json.load(response)
    except HTTPError as failure:
        try:
            code = json.load(failure).get("code", f"HTTP_{failure.code}")
        except (ValueError, AttributeError):
            code = f"HTTP_{failure.code}"
        raise RuntimeError(f"Request rejected: {code}") from None


def write_private(path, value):
    # Refuse symlinks and existing files rather than overwrite a prior export or token.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(value, output, indent=2)
        output.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="Application HTTPS origin, with no path")
    parser.add_argument("--token-file", type=Path, help="Private token file created by login")
    sub = parser.add_subparsers(dest="command", required=True)
    login = sub.add_parser("login", help="Sign in through Firebase; password is prompted, never stored")
    login.add_argument("--email", required=True)
    listing = sub.add_parser("list")
    listing.add_argument("--status", default="all")
    export = sub.add_parser("export")
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--include-archives", action="store_true")
    for name in ("validate", "apply"):
        command = sub.add_parser(name)
        command.add_argument("id")
        command.add_argument("--revision", type=int, required=True)
        command.add_argument("--file", type=Path, required=True, help="One exported game containing an entries array")
    for name in ("cancel", "abandon", "purge"):
        command = sub.add_parser(name)
        command.add_argument("id")
        command.add_argument("--revision", type=int, required=True)
    args = parser.parse_args()
    origin = args.url.rstrip("/")
    parsed = urlparse(origin)
    if parsed.scheme != "https" or not parsed.hostname or parsed.path or parsed.query or parsed.fragment or parsed.username:
        parser.error("Use an HTTPS origin without credentials, path or query.")
    if args.command == "login":
        if not args.token_file:
            parser.error("login requires --token-file; the file must not already exist.")
        config = http(origin + "/api/config")
        if config.get("authMode") != "firebase":
            parser.error("This application does not use hosted authentication.")
        key = config["firebase"]["apiKey"]
        identity = http("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?" + urlencode({"key": key}),
                        body={"email": args.email, "password": getpass.getpass("Password: "), "returnSecureToken": True})
        token = identity["idToken"]
        me = http(origin + "/api/me", token)
        if not (me.get("account") or {}).get("admin"):
            raise RuntimeError("This verified account is not an application administrator.")
        write_private(args.token_file, {"origin": origin, "idToken": token})
        print("Signed in. The token expires in about one hour; no password or refresh token was saved.")
        return
    token = os.environ.get("AOR_ADMIN_TOKEN")
    if args.token_file:
        saved = json.loads(args.token_file.read_text())
        if saved["origin"] != origin:
            parser.error("The token file belongs to a different origin.")
        token = saved["idToken"]
    if not token:
        parser.error("Supply --token-file or AOR_ADMIN_TOKEN.")

    def api(path, body=None):
        return http(origin + "/api/admin" + path, token, body)

    if args.command in ("list", "export"):
        games = []
        after = None
        while True:
            query = {"limit": 20}
            if after:
                query["after"] = after
            if args.command == "list":
                query["status"] = args.status
            page = api(("/games" if args.command == "list" else "/logs") + "?" + urlencode(query))
            games.extend(page["games"])
            after = page["nextCursor"]
            if not after:
                break
        if args.command == "list":
            print(json.dumps(games, indent=2))
        else:
            if args.include_archives:
                for game in games:
                    game["archivedLogs"] = [api(f"/games/{quote(game['id'], safe='')}/log?" + urlencode({"archive": archive})) for archive in game["archives"]]
            write_private(args.output, {"games": games})
            print(f"Exported {len(games)} private game logs to {args.output}.")
        return
    path = "/games/" + quote(args.id, safe="")
    body = {"expectedRevision": args.revision}
    if args.command in ("validate", "apply"):
        body.update(entries=json.loads(args.file.read_text())["entries"], dryRun=args.command == "validate")
        result = api(path + "/log", body)
    elif args.command == "purge":
        result = api(path + "/purge", body)
    else:
        result = api(path + "/status", dict(body, action=args.command))
    # Validation metadata is useful; successful writes need no copy of sensitive log contents.
    print(json.dumps(result if args.command == "validate" else {"id": args.id, "operation": args.command, "ok": True}))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as failure:
        print(f"Error: {failure}", file=sys.stderr)
        sys.exit(1)
