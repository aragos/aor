#!/usr/bin/env python3
"""CI smoke check: the production image connects to an isolated PostgreSQL service as non-root."""
import gzip
import json
import os
import re
import subprocess
import time
from urllib.request import Request, urlopen
from urllib.error import URLError


def verify_built_client(origin):
    """Verify the deployed entry script and the map drawings it loads separately."""
    with urlopen(origin, timeout=5) as response:
        html = response.read().decode()
    scripts = re.findall(r'<script\b[^>]*\bsrc=["\'](/assets/[^"\']+\.js)["\']', html)
    if not scripts:
        raise RuntimeError("The production server did not serve the built client entry script.")
    drawings = set()
    for path in scripts:
        with urlopen(origin + path, timeout=5) as response:
            javascript = response.read().decode()
        drawings.update(re.findall(r'["\'](/assets/[^"\'\s]+Geometry-[^"\'\s]+\.json)["\']', javascript))
    if not drawings:
        raise RuntimeError("The built client does not reference separately loaded map drawings.")
    required = {"bounds", "frame", "provinces", "coasts", "areas", "seas", "terrain", "supports"}
    for path in sorted(drawings):
        request = Request(origin + path, headers={"Accept-Encoding": "gzip"})
        with urlopen(request, timeout=10) as response:
            if response.headers.get_content_type() != "application/json":
                raise RuntimeError(f"Map drawing {path} must be served as application/json.")
            cache = response.headers.get("Cache-Control", "")
            if "max-age=31536000" not in cache or "immutable" not in cache:
                raise RuntimeError(f"Hashed map drawing {path} must have immutable caching.")
            if response.headers.get("Content-Encoding") != "gzip":
                raise RuntimeError(f"Map drawing {path} must support gzip compression.")
            geometry = json.loads(gzip.decompress(response.read()))
        if (not isinstance(geometry, dict) or not required.issubset(geometry)
                or not isinstance(geometry["bounds"], list) or len(geometry["bounds"]) != 4
                or not isinstance(geometry["provinces"], dict) or not geometry["provinces"]):
            raise RuntimeError(f"Map drawing {path} does not contain usable geometry.")
    return len(drawings)


def main():
    database = os.environ["AOR_TEST_DATABASE_URL"]
    if not database.startswith(("postgresql://postgres:postgres@127.0.0.1:", "postgres://postgres:postgres@127.0.0.1:")):
        raise RuntimeError("The smoke check requires the isolated loopback PostgreSQL test URL.")
    name = "aor-container-check"
    command = ["docker", "run", "--detach", "--rm", "--network", "host", "--name", name,
               "--env", "AOR_DATABASE_URL", "--env", "AOR_DATABASE_SCHEMA=container_check",
               "--env", "AOR_AUTH_MODE=local", "--env", "AOR_HTTP_HOST=127.0.0.1",
               "--env", "AOR_HTTP_PORT=18080", "aor:verify"]
    environment = dict(os.environ, AOR_DATABASE_URL=database)
    subprocess.run(command, check=True, env=environment, stdout=subprocess.DEVNULL)
    try:
        origin = "http://127.0.0.1:18080"
        for attempt in range(100):
            try:
                with urlopen(origin + "/api/health", timeout=2) as response:
                    if response.status == 200:
                        break
            except URLError:
                time.sleep(.2)
        else:
            raise RuntimeError("The production container did not become ready.")
        uid = subprocess.check_output(["docker", "exec", name, "id", "-u"], text=True).strip()
        if uid != "10001":
            raise RuntimeError("The application must run as UID 10001.")
        request = Request(origin + "/api/games", data=json.dumps({"players": ["Alice", "Bob", "Carol"]}).encode(),
                          headers={"Content-Type": "application/json"})
        with urlopen(request, timeout=10) as response:
            game = json.load(response)
            if response.status != 201 or len(game["links"]) != 3:
                raise RuntimeError("The production container could not persist a game.")
        drawings = verify_built_client(origin)
        print(f"Passed: non-root production startup, SQL migrations, health, game persistence, "
              f"built client and {drawings} cached, compressed map drawings.")
    except Exception:
        subprocess.run(["docker", "logs", name], check=False)
        raise
    finally:
        subprocess.run(["docker", "stop", name], check=False, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
