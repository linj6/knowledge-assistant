"""Run against the existing Compose MinIO, or an explicitly selected test container."""
import argparse
import json
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bucket", default="knowledge-assistant")
    parser.add_argument("--container", help="Test container ID; otherwise use docker compose exec minio")
    parser.add_argument("--check", action="store_true", help="Read-only deployment check")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", args.bucket):
        raise RuntimeError("Invalid bucket name")
    base = ["docker", "exec", args.container] if args.container else ["docker", "compose", "exec", "-T", "minio"]

    def run(command):
        return subprocess.run(base + command, capture_output=True, text=True)

    # Credentials expand only inside the container; never print command output on errors.
    alias = run(["sh", "-c", 'mc alias set ka-ilm http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null'])
    if alias.returncode:
        raise RuntimeError("MinIO alias setup failed")
    target = "ka-ilm/" + args.bucket

    def export():
        result = run(["mc", "--json", "ilm", "rule", "export", target])
        try:
            data = json.loads(result.stdout)
        except ValueError:
            raise RuntimeError("Cannot read lifecycle configuration") from None
        if result.returncode:
            if data.get("error", {}).get("cause", {}).get("error", {}).get("Code") == "NoSuchLifecycleConfiguration":
                return {"Rules": []}
            raise RuntimeError("Lifecycle query failed; no configuration changed")
        if data.get("status") != "success" or not isinstance(data.get("config", {}).get("Rules"), list):
            raise RuntimeError("Unrecognized lifecycle response")
        return data["config"]

    def validate(config):
        found = False
        for rule in config.get("Rules", []):
            prefix = rule.get("Filter", {}).get("Prefix", rule.get("Prefix", ""))
            if rule.get("Status") != "Enabled":
                continue
            if "Expiration" in rule and (not prefix or "originals/".startswith(prefix)):
                raise RuntimeError("Unsafe existing expiration rule; review manually, no rules overwritten")
            if prefix == "staging/":
                if rule.get("Filter", {}) != {"Prefix": "staging/"} or rule.get("Expiration") != {"Days": 2}:
                    raise RuntimeError("Conflicting staging rule; review manually")
                found = True
        return found

    config = export()
    if not validate(config):
        if args.check:
            raise RuntimeError("Missing staging ILM; rerun without --check to install")
        # mc adds a rule while preserving existing rules. Serialize infrastructure changes operationally.
        result = run(["mc", "ilm", "rule", "add", "--prefix", "staging/", "--expire-days", "2", target])
        if result.returncode:
            raise RuntimeError("Lifecycle installation failed")
        config = export()
        if not validate(config):
            raise RuntimeError("Lifecycle verification failed")
    print(json.dumps({"verified": True, "prefix": "staging/", "expireDays": 2, "ruleCount": len(config.get("Rules", []))}))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError) as error:
        print("ILM_CHECK_FAILED: " + str(error), file=sys.stderr)
        sys.exit(1)
