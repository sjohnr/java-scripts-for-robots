"""Tests for the jarsrc CLI. Run with: python3 -m unittest test_jarsrc"""

import importlib.machinery
import os
import subprocess
import sys
import tempfile
import types
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
JARSRC = os.path.join(HERE, "jarsrc")

# The script has no .py suffix, so load it by path to reach its helpers directly.
_loader = importlib.machinery.SourceFileLoader("jarsrc", JARSRC)
jarsrc = types.ModuleType(_loader.name)
_loader.exec_module(jarsrc)


def make_jar(root, group, artifact, version, sources):
    """Write <root>/<group as dirs>/<artifact>/<version>/<artifact>-<version>-sources.jar."""
    directory = os.path.join(root, *group.split("."), artifact, version)
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, f"{artifact}-{version}-sources.jar")
    with zipfile.ZipFile(path, "w") as zf:
        for name, text in sources.items():
            zf.writestr(name, text)


class CliTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        make_jar(self.root, "com.example.lib", "widget", "1.9", {
            "com/example/lib/Widget.java": "package com.example.lib;\nclass Widget { /* 1.9 */ }\n",
        })
        make_jar(self.root, "com.example.lib", "widget", "1.10", {
            "com/example/lib/Widget.java": "line one  \r\nline two\r\n\r\nline four\t\n",
            "com/example/lib/Helper.java": "class Helper {}\n",
            "com/example/lib/readme.txt": "not java\n",
        })
        make_jar(self.root, "com.example.lib", "gizmo", "1.0", {
            "com/example/lib/Gizmo.java": "class Gizmo {}\n",
        })

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *args):
        return subprocess.run(
            [sys.executable, JARSRC, *args],
            capture_output=True,
            cwd=self.root,
        )

    def assertOutput(self, result, stdout, code=0, stderr=b""):
        self.assertEqual(result.returncode, code, result.stderr)
        self.assertEqual(result.stdout, stdout.encode("utf-8"))
        self.assertEqual(result.stderr, stderr)

    # --- version ordering ---

    def test_version_ordering(self):
        cmp = jarsrc.compare_versions
        self.assertGreater(cmp("1.2.0", "1.2"), 0)
        self.assertGreater(cmp("1.10.0", "1.9.0"), 0)
        self.assertLess(cmp("1.0.0", "1.0.0-beta"), 0)
        self.assertGreater(cmp("1.0.10", "1.0.9"), 0)
        self.assertLess(cmp("1.0.a", "1.0.b"), 0)
        self.assertLess(cmp("1.0.9", "1.0.a"), 0)  # numeric segment vs non-numeric: ordinal

    # --- find ---

    def test_find_exact_lists_every_version(self):
        r = self.run_cli("find", "com.example.lib.Widget", "--root", self.root)
        self.assertOutput(
            r,
            "com.example.lib.Widget com.example.lib:widget:1.10\n"
            "com.example.lib.Widget com.example.lib:widget:1.9\n",
        )

    def test_find_glob(self):
        r = self.run_cli("find", "com.example.lib.*", "--root", self.root)
        self.assertOutput(
            r,
            "com.example.lib.Gizmo com.example.lib:gizmo:1.0\n"
            "com.example.lib.Helper com.example.lib:widget:1.10\n"
            "com.example.lib.Widget com.example.lib:widget:1.10\n"
            "com.example.lib.Widget com.example.lib:widget:1.9\n",
        )

    def test_find_double_star_crosses_dots(self):
        r = self.run_cli("find", "com.**.Gizmo", "--root", self.root)
        self.assertOutput(r, "com.example.lib.Gizmo com.example.lib:gizmo:1.0\n")

    def test_find_single_star_does_not_cross_dots(self):
        r = self.run_cli("find", "com.*.Gizmo", "--root", self.root)
        self.assertOutput(r, "", code=1,
                          stderr=b"error: no matches for com.*.Gizmo (did you mean: Gizmo?)\n")

    def test_find_no_match_suggests_by_simple_name(self):
        r = self.run_cli("find", "com.example.lib.Wid", "--root", self.root)
        self.assertOutput(r, "", code=1,
                          stderr=b"error: no matches for com.example.lib.Wid (did you mean: Widget?)\n")

    # --- cat ---

    def test_cat_uses_highest_version_and_normalizes(self):
        r = self.run_cli("cat", "com.example.lib.Widget", "--root", self.root)
        self.assertOutput(r, "line one\nline two\n\nline four\n")

    def test_cat_line_range(self):
        r = self.run_cli("cat", "com.example.lib.Widget", "--lines", "2:3", "--root", self.root)
        self.assertOutput(r, "line two\n\n")

    def test_cat_line_range_clamps(self):
        r = self.run_cli("cat", "com.example.lib.Widget", "--lines", "0:99", "--root", self.root)
        self.assertOutput(r, "line one\nline two\n\nline four\n")

    def test_cat_line_range_past_end_is_empty_success(self):
        r = self.run_cli("cat", "com.example.lib.Widget", "--lines", "10:20", "--root", self.root)
        self.assertOutput(r, "")

    def test_cat_not_found(self):
        r = self.run_cli("cat", "com.example.lib.Gadget", "--root", self.root)
        self.assertOutput(r, "", code=1,
                          stderr=b"error: no source for com.example.lib.Gadget\n")

    def test_cat_not_found_suggestion(self):
        r = self.run_cli("cat", "com.example.lib.Hel", "--root", self.root)
        self.assertOutput(r, "", code=1,
                          stderr=b"error: no source for com.example.lib.Hel (did you mean: Helper?)\n")

    def test_cat_ambiguous_across_artifacts(self):
        make_jar(self.root, "org.other", "clone", "2.0", {
            "com/example/lib/Widget.java": "class Widget {}\n",
        })
        make_jar(self.root, "org.aaa", "clone", "1.0", {
            "com/example/lib/Widget.java": "class Widget {}\n",
        })
        r = self.run_cli("cat", "com.example.lib.Widget", "--root", self.root)
        self.assertOutput(
            r, "", code=2,
            stderr=(b"error: com.example.lib.Widget is ambiguous across: "
                    b"com.example.lib:widget, org.aaa:clone, org.other:clone\n"),
        )

    # --- ls ---

    def test_ls_artifact_versions_sorted_numerically(self):
        make_jar(self.root, "com.example.lib", "widget", "1.2", {"a/B.java": ""})
        r = self.run_cli("ls", "com.example.lib:widget", "--root", self.root)
        self.assertOutput(r, "1.2\n1.9\n1.10\n")

    def test_ls_version_lists_classes(self):
        r = self.run_cli("ls", "com.example.lib:widget:1.10", "--root", self.root)
        self.assertOutput(r, "com.example.lib.Helper\ncom.example.lib.Widget\n")

    def test_ls_artifact_not_found(self):
        r = self.run_cli("ls", "com.example.lib:widg", "--root", self.root)
        self.assertOutput(r, "", code=1,
                          stderr=b"error: no artifact com.example.lib:widg (did you mean: com.example.lib:widget?)\n")

    def test_ls_artifact_not_found_without_prefix_match(self):
        r = self.run_cli("ls", "com.example.lib:widgt", "--root", self.root)
        self.assertOutput(r, "", code=1,
                          stderr=b"error: no artifact com.example.lib:widgt\n")

    def test_ls_version_not_found(self):
        r = self.run_cli("ls", "com.example.lib:widget:2.0", "--root", self.root)
        self.assertOutput(r, "", code=1,
                          stderr=b"error: no artifact com.example.lib:widget:2.0 (did you mean: com.example.lib:widget?)\n")

    # --- flags and usage ---

    def test_help_lists_flags(self):
        r = self.run_cli("--help")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stderr, b"")
        for word in (b"find", b"cat", b"ls", b"--root", b"--lines"):
            self.assertIn(word, r.stdout)

    def test_unknown_flag(self):
        r = self.run_cli("find", "com.X", "--bogus")
        self.assertOutput(r, "", code=2, stderr=b"unknown flag: --bogus\n")

    def test_lines_is_not_valid_for_find(self):
        r = self.run_cli("find", "com.X", "--lines", "1:2")
        self.assertOutput(r, "", code=2, stderr=b"unknown flag: --lines\n")

    def test_bad_lines_value(self):
        r = self.run_cli("cat", "com.example.lib.Widget", "--lines", "a:b")
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b"")

    def test_missing_argument(self):
        r = self.run_cli("cat")
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b"")

    def test_root_flag_equals_form(self):
        r = self.run_cli("ls", "com.example.lib:gizmo", f"--root={self.root}")
        self.assertOutput(r, "1.0\n")

    def test_missing_root_is_not_found(self):
        r = subprocess.run([sys.executable, JARSRC, "ls", "a:b", "--root", os.path.join(self.root, "nope")],
                           capture_output=True)
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stderr, b"error: no artifact a:b\n")


if __name__ == "__main__":
    unittest.main()
