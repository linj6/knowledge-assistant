"""Script checks use a fake mc transport; StorageImportIT verifies the real mc/MinIO path."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("staging_ilm", Path(__file__).with_name("configure-staging-ilm.py"))
ilm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ilm)


def rule(prefix="staging/", days=2, status="Enabled"):
    return {"ID": "existing", "Status": status, "Filter": {"Prefix": prefix}, "Expiration": {"Days": days}}


def conflicts():
    values = [rule(prefix) for prefix in ("", "stag", "staging", "originals", "originals/", "originals/private/", "staging/private/")]
    values += [rule(days=1), rule(days=3), {"Status": "Enabled", "Expiration": {"Days": 1}}]
    for filter_value in ({"Tag": {"Key": "kind", "Value": "temp"}},
                         {"And": {"Prefix": "staging/", "Tags": [{"Key": "kind", "Value": "temp"}]}},
                         {"And": {"Tags": [{"Key": "kind", "Value": "temp"}]}},
                         {"Prefix": "archive/", "Unknown": True}):
        candidate = rule()
        candidate["Filter"] = filter_value
        values.append(candidate)
    candidate = rule("originals/")
    candidate["NoncurrentVersionExpiration"] = {"NoncurrentDays": 1}
    del candidate["Expiration"]
    values.append(candidate)
    return values


class StagingIlmTests(unittest.TestCase):
    def test_empty_exact_archive_and_disabled(self):
        self.assertFalse(ilm.validate({"Rules": []}))
        self.assertTrue(ilm.validate({"Rules": [rule()]}))
        self.assertFalse(ilm.validate({"Rules": [rule("archive/", 30)]}))
        self.assertTrue(ilm.validate({"Rules": [rule("", 1, "Disabled"), rule()]}))
        archive = rule("archive/", 30)
        archive["Filter"] = {"And": {"Prefix": "archive/", "Tags": [{"Key": "kind", "Value": "old"}]}}
        self.assertTrue(ilm.validate({"Rules": [archive, rule()]}))
        legacy = rule()
        legacy["Prefix"] = legacy.pop("Filter")["Prefix"]
        self.assertTrue(ilm.validate({"Rules": [legacy]}))

    def test_all_conflicts_even_when_exact_rule_exists(self):
        for conflict in conflicts():
            with self.subTest(conflict=conflict):
                with self.assertRaisesRegex(RuntimeError, "review manually|rejected"):
                    ilm.validate({"Rules": [rule(), conflict]})

    def run_cli(self, config, check=False):
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            if "export" in command:
                return subprocess.CompletedProcess(command, 0, json.dumps({"status": "success", "config": copy.deepcopy(config)}), "")
            if "add" in command:
                config["Rules"].append(rule())
            return subprocess.CompletedProcess(command, 0, "", "")

        args = ["configure-staging-ilm.py", "--bucket", "knowledge-assistant"] + (["--check"] if check else [])
        with patch.object(ilm.subprocess, "run", side_effect=run), patch("sys.argv", args), patch("builtins.print"):
            ilm.main()
        return calls

    def test_repeated_configuration_preserves_other_rules(self):
        for initial in ([], [rule("archive/", 30)], [rule("", 1, "Disabled")]):
            with self.subTest(initial=initial):
                config = {"Rules": copy.deepcopy(initial)}
                self.run_cli(config)
                self.assertEqual(config["Rules"][:-1], initial)
                snapshot = copy.deepcopy(config)
                self.assertFalse(any("add" in c for c in self.run_cli(config)))
                self.assertFalse(any("add" in c for c in self.run_cli(config, check=True)))
                self.assertEqual(config, snapshot)

    def test_missing_check_does_not_install(self):
        config = {"Rules": []}
        with self.assertRaisesRegex(RuntimeError, "Missing staging"):
            self.run_cli(config, check=True)
        self.assertEqual(config, {"Rules": []})

    def test_rejection_never_changes_configuration(self):
        for conflict in conflicts():
            for check in (False, True):
                with self.subTest(conflict=conflict, check=check):
                    config = {"Rules": [rule("archive/", 30), conflict]}
                    snapshot = copy.deepcopy(config)
                    with self.assertRaises(RuntimeError):
                        self.run_cli(config, check=check)
                    self.assertEqual(config, snapshot)

    def test_disabled_conflicts_do_not_block_installation(self):
        config = {"Rules": [dict(conflict, Status="Disabled") for conflict in conflicts()]}
        before = copy.deepcopy(config["Rules"])
        self.run_cli(config)
        self.assertEqual(config["Rules"][:-1], before)
        self.assertTrue(ilm.validate(config))


if __name__ == "__main__":
    unittest.main()
