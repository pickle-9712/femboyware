"""Publishes build/libs/<version>/ as a GitHub release of pickle-9712/femboyware.

femboyware.xyz serves the jars of the newest release, so this is all it takes to update the site.
Run from the repo root after `./gradlew buildAll` and pushing the commit:

    python scripts/github_release.py            # the version in stonecutter.properties.toml
    python scripts/github_release.py 1.0.50     # a given version

The token comes from Git Credential Manager (the same login git push uses) and is never printed.
"""
import json
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "pickle-9712/femboyware"
ROOT = pathlib.Path(__file__).resolve().parent.parent


def token():
    out = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                         capture_output=True, text=True, check=True).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    sys.exit("No GitHub login saved in Git Credential Manager")


def call(method, url, auth, body=None, data=None, content_type="application/json"):
    headers = {"Authorization": "Bearer " + auth, "Accept": "application/vnd.github+json", "User-Agent": "femboyware-release"}
    if body is not None:
        data = json.dumps(body).encode()
    if data is not None:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        if e.code == 404 and method == "GET":
            return None
        sys.exit(f"GitHub said {e.code} to {method} {url}: {e.read().decode(errors='replace')[:300]}")


def main():
    version = sys.argv[1] if len(sys.argv) > 1 else re.search(r'mod\.version\s*=\s*"([^"]+)"', (ROOT / "stonecutter.properties.toml").read_text()).group(1)
    jars = sorted((ROOT / "build" / "libs" / version).glob(f"femboyware-{version}+*.jar"))
    if len(jars) < 3:
        sys.exit(f"Expected 3 jars in build/libs/{version}, found {len(jars)}: run ./gradlew buildAll")
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    subject = subprocess.run(["git", "log", "-1", "--format=%s"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    auth = token()
    tag = "v" + version
    release = call("GET", f"https://api.github.com/repos/{REPO}/releases/tags/{tag}", auth)
    if release is None:
        release = call("POST", f"https://api.github.com/repos/{REPO}/releases", auth, {
            "tag_name": tag, "target_commitish": commit, "name": f"femboyware {version}", "body": subject, "make_latest": "true"})
    existing = {asset["name"]: asset["id"] for asset in release.get("assets", [])}
    upload = release["upload_url"].split("{")[0]
    for jar in jars:
        if jar.name in existing:
            call("DELETE", f"https://api.github.com/repos/{REPO}/releases/assets/{existing[jar.name]}", auth)
        call("POST", f"{upload}?name={jar.name}", auth, data=jar.read_bytes(), content_type="application/java-archive")
        print("uploaded", jar.name)
    print("released", tag, release["html_url"])


if __name__ == "__main__":
    main()
