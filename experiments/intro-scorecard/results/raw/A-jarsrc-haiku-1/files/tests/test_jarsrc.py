import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

JARSRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "jarsrc")


def make_jar(root, group, artifact, version, classes):
    directory = os.path.join(root, *group.split("."), artifact, version)
    os.makedirs(directory, exist_ok=True)
    path = os.path.join(directory, f"{artifact}-{version}-sources.jar")
    with zipfile.ZipFile(path, "w") as zf:
        for fqcn, body in classes.items():
            zf.writestr(fqcn.replace(".", "/") + ".java", body)


def run(*args):
    proc = subprocess.run([sys.executable, JARSRC, *args], capture_output=True)
    return proc.returncode, proc.stdout.decode("utf-8"), proc.stderr.decode("utf-8")


class JarsrcTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        make_jar(self.root, "com.example", "lib", "1.0", {
            "com.example.lib.Widget": "class Widget {}\n",
            "com.example.lib.util.Helpers": "class Helpers {}\n",
        })
        make_jar(self.root, "com.example", "lib", "2.0", {
            "com.example.lib.Widget": "line1\r\nline2\r\nline3  \r\n",
        })
        make_jar(self.root, "com.other", "x", "1.0", {
            "com.other.x.Gadget": "class Gadget {}\n",
        })

    def tearDown(self):
        self._tmp.cleanup()

    def test_find_exact_lists_every_version_sorted(self):
        code, out, err = run("find", "com.example.lib.Widget", "--root", self.root)
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(out, (
            "com.example.lib.Widget com.example:lib:1.0\n"
            "com.example.lib.Widget com.example:lib:2.0\n"
        ))

    def test_find_glob_star_does_not_cross_dots(self):
        code, out, _ = run("find", "com.example.lib.*", "--root", self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out.splitlines(), [
            "com.example.lib.Widget com.example:lib:1.0",
            "com.example.lib.Widget com.example:lib:2.0",
        ])

    def test_find_double_star_crosses_dots(self):
        code, out, _ = run("find", "com.**", "--root", self.root)
        self.assertEqual(code, 0)
        self.assertIn("com.example.lib.util.Helpers com.example:lib:1.0\n", out)
        self.assertIn("com.other.x.Gadget com.other:x:1.0\n", out)

    def test_find_single_star_does_not_match_nested(self):
        code, out, err = run("find", "com.example.*", "--root", self.root)
        self.assertEqual((code, out), (1, ""))
        self.assertEqual(err, "error: no matches for com.example.*\n")

    def test_find_no_match_suggests_simple_names(self):
        code, out, err = run("find", "com.example.lib.Wid", "--root", self.root)
        self.assertEqual((code, out), (1, ""))
        self.assertEqual(err, "error: no matches for com.example.lib.Wid (did you mean: Widget?)\n")

    def test_cat_uses_highest_version(self):
        code, out, err = run("cat", "com.example.lib.Widget", "--root", self.root, "--lines", "1:2")
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(out, "line1\nline2\n")

    def test_cat_full_output_strips_trailing_whitespace_and_crlf(self):
        code, out, _ = run("cat", "com.example.lib.Widget", "--root", self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out, "line1\nline2\nline3\n")

    def test_cat_line_range_clamps(self):
        code, out, _ = run("cat", "com.example.lib.Widget", "--root", self.root, "--lines", "2:99")
        self.assertEqual((code, out), (0, "line2\nline3\n"))

    def test_cat_line_range_past_end_prints_nothing(self):
        code, out, err = run("cat", "com.example.lib.Widget", "--root", self.root, "--lines", "9:12")
        self.assertEqual((code, out, err), (0, "", ""))

    def test_cat_ambiguous_is_usage_error(self):
        make_jar(self.root, "com.other", "x", "2.0", {"com.example.lib.Widget": "class Other {}\n"})
        make_jar(self.root, "com.third", "y", "1.0", {"com.example.lib.Widget": "x\n"})
        code, out, err = run("cat", "com.example.lib.Widget", "--root", self.root)
        self.assertEqual((code, out), (2, ""))
        self.assertEqual(
            err,
            "error: com.example.lib.Widget is ambiguous across: com.example:lib, com.other:x, com.third:y\n",
        )

    def test_cat_not_found_suggests(self):
        code, out, err = run("cat", "com.example.lib.Wid", "--root", self.root)
        self.assertEqual((code, out), (1, ""))
        self.assertEqual(err, "error: no source for com.example.lib.Wid (did you mean: Widget?)\n")

    def test_ls_versions_use_version_ordering(self):
        make_jar(self.root, "com.ver", "v", "1.10.0", {"a.A": ""})
        make_jar(self.root, "com.ver", "v", "1.9.0", {"a.A": ""})
        make_jar(self.root, "com.ver", "v", "1.0.0-beta", {"a.A": ""})
        make_jar(self.root, "com.ver", "v", "1.0.0", {"a.A": ""})
        code, out, _ = run("ls", "com.ver:v", "--root", self.root)
        self.assertEqual((code, out), (0, "1.0.0\n1.0.0-beta\n1.9.0\n1.10.0\n"))

    def test_ls_single_version_lists_classes(self):
        code, out, _ = run("ls", "com.example:lib:1.0", "--root", self.root)
        self.assertEqual((code, out), (0, "com.example.lib.Widget\ncom.example.lib.util.Helpers\n"))

    def test_ls_missing_version_suggests_artifact(self):
        code, out, err = run("ls", "com.example:lib:9.9", "--root", self.root)
        self.assertEqual((code, out), (1, ""))
        self.assertEqual(err, "error: no artifact com.example:lib:9.9 (did you mean: com.example:lib?)\n")

    def test_ls_missing_artifact(self):
        code, _, err = run("ls", "com.nope:thing", "--root", self.root)
        self.assertEqual((code, err), (1, "error: no artifact com.nope:thing\n"))

    def test_unknown_flag(self):
        code, out, err = run("find", "com.x", "--bogus")
        self.assertEqual((code, out, err), (2, "", "unknown flag: --bogus\n"))

    def test_lines_flag_rejected_outside_cat(self):
        code, _, err = run("find", "com.x", "--lines", "1:2", "--root", self.root)
        self.assertEqual((code, err), (2, "unknown flag: --lines\n"))

    def test_help_lists_flags_and_exits_zero(self):
        code, out, err = run("--help")
        self.assertEqual((code, err), (0, ""))
        for token in ("find", "cat", "ls", "--root", "--lines"):
            self.assertIn(token, out)
        for line in out.split("\n"):
            self.assertEqual(line, line.rstrip())

    def test_output_has_no_crlf(self):
        _, out, _ = run("cat", "com.example.lib.Widget", "--root", self.root)
        self.assertNotIn("\r", out)


if __name__ == "__main__":
    unittest.main()
