"""Run against the existing Compose MinIO, or an explicitly selected test container."""
import argparse
import json
import re
import subprocess
import sys


def validate(config):
    """Only recognize simple prefix expiry; do not infer safety from tags."""
    found = False
    for rule in config.get("Rules", []):
        if rule.get("Status") == "Disabled":
            continue
        if not any(action in rule for action in ("Expiration", "NoncurrentVersionExpiration")):
            continue
        if rule.get("Status") != "Enabled":
            raise RuntimeError("Unknown expiration rule status; review manually, no rules changed")
        rule_filter = rule.get("Filter")
        simple = True
        if rule_filter is None:
            prefix = rule.get("Prefix", "")
        elif isinstance(rule_filter, dict) and "Prefix" not in rule:
            simple = set(rule_filter) <= {"Prefix"}
            if simple:
                prefix = rule_filter.get("Prefix", "")
            elif set(rule_filter) == {"And"} and isinstance(rule_filter["And"], dict):
                # And中的明确前缀能排除archive，但不能据此接受相关对象的复合规则。
                prefix = rule_filter["And"].get("Prefix")
            else:
                prefix = None
        else:
            prefix = None
        if not isinstance(prefix, str):
            raise RuntimeError("Cannot determine expiration filter scope (Tag/compound); review manually, no rules changed")
        overlaps = lambda directory: directory.startswith(prefix) or prefix.startswith(directory)
        if overlaps("originals/"):
            raise RuntimeError("Expiration may match originals/; rejected, no rules changed")
        if not overlaps("staging/"):
            continue
        if not simple:
            raise RuntimeError("Compound expiration may match staging/; review manually, no rules changed")
        if prefix != "staging/" or rule.get("Expiration") != {"Days": 2} or "NoncurrentVersionExpiration" in rule:
            raise RuntimeError("Conflicting staging expiration prefix or duration; review manually, no rules changed")
        found = True
    return found


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
