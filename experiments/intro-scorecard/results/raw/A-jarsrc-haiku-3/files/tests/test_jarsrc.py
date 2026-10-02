import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT_DIR, "jarsrc")


def make_jar(root, group, artifact, version, classes):
    """group may use dots (dots-as-directories layout is produced by splitting on '.')."""
    d = os.path.join(root, *group.split("."), artifact, version)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, f"{artifact}-{version}-sources.jar")
    with zipfile.ZipFile(path, "w") as zf:
        for fqcn, body in classes.items():
            zf.writestr(fqcn.replace(".", "/") + ".java", body)


class JarsrcTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        make_jar(self.root, "com.example.lib", "widget", "1.9.0", {
            "com.example.lib.Widget": "class Widget {}\n",
            "com.example.lib.WidgetUtil": "class WidgetUtil {}\n",
        })
        make_jar(self.root, "com.example.lib", "widget", "1.10.0", {
            "com.example.lib.Widget": "line1\r\nline2  \nline3\nline4\n",
        })
        make_jar(self.root, "com.other", "thing", "2.0", {
            "com.other.Widget": "class OtherWidget {}\n",
        })

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *args):
        proc = subprocess.run(
            [sys.executable, SCRIPT, *args],
            capture_output=True,
            check=False,
        )
        return proc.returncode, proc.stdout.decode("utf-8"), proc.stderr.decode("utf-8")

    def test_find_exact_lists_each_version(self):
        make_jar(self.root, "com.example.lib", "widget", "1.8", {"com.example.lib.Gizmo": ""})
        make_jar(self.root, "com.example.lib", "widget", "1.7", {"com.example.lib.Gizmo": ""})
        code, out, err = self.run_cli("find", "com.example.lib.Gizmo", "--root", self.root)
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(out, "com.example.lib.Gizmo com.example.lib:widget:1.7\n"
                              "com.example.lib.Gizmo com.example.lib:widget:1.8\n")

    def test_find_glob_star_does_not_cross_dots(self):
        code, out, _ = self.run_cli("find", "com.example.lib.*", "--root", self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out, "com.example.lib.Widget com.example.lib:widget:1.10.0\n"
                              "com.example.lib.Widget com.example.lib:widget:1.9.0\n"
                              "com.example.lib.WidgetUtil com.example.lib:widget:1.9.0\n")

    def test_find_double_star_crosses_dots(self):
        code, out, _ = self.run_cli("find", "com.**.Widget", "--root", self.root)
        self.assertEqual(code, 0)
        self.assertEqual(out, "com.example.lib.Widget com.example.lib:widget:1.10.0\n"
                              "com.example.lib.Widget com.example.lib:widget:1.9.0\n"
                              "com.other.Widget com.other:thing:2.0\n")

    def test_find_no_match_suggests_by_simple_name(self):
        code, out, err = self.run_cli("find", "com.example.lib.Wid", "--root", self.root)
        self.assertEqual((code, out), (1, ""))
        self.assertEqual(err, "error: no matches for com.example.lib.Wid "
                              "(did you mean: Widget, WidgetUtil?)\n")

    def test_cat_uses_highest_version_and_normalises_output(self):
        code, out, err = self.run_cli("cat", "com.example.lib.Widget", "--root", self.root)
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(out, "line1\nline2\nline3\nline4\n")

    def test_cat_lines_clamps_and_slices(self):
        code, out, _ = self.run_cli("cat", "com.example.lib.Widget", "--lines", "2:99", "--root", self.root)
        self.assertEqual((code, out), (0, "line2\nline3\nline4\n"))
        code, out, _ = self.run_cli("cat", "com.example.lib.Widget", "--lines", "9:12", "--root", self.root)
        self.assertEqual((code, out), (0, ""))

    def test_cat_ambiguous_across_artifacts(self):
        make_jar(self.root, "com.other", "thing", "2.1", {"com.example.lib.Widget": "x\n"})
        code, out, err = self.run_cli("cat", "com.example.lib.Widget", "--root", self.root)
        self.assertEqual((code, out), (2, ""))
        self.assertEqual(err, "error: com.example.lib.Widget is ambiguous across: "
                              "com.example.lib:widget, com.other:thing\n")

    def test_cat_not_found(self):
        code, out, err = self.run_cli("cat", "com.example.lib.Nope", "--root", self.root)
        self.assertEqual((code, out, err), (1, "", "error: no source for com.example.lib.Nope\n"))

    def test_ls_versions_sorted_numerically(self):
        code, out, _ = self.run_cli("ls", "com.example.lib:widget", "--root", self.root)
        self.assertEqual((code, out), (0, "1.9.0\n1.10.0\n"))

    def test_ls_gav_lists_classes(self):
        code, out, _ = self.run_cli("ls", "com.example.lib:widget:1.9.0", "--root", self.root)
        self.assertEqual((code, out), (0, "com.example.lib.Widget\ncom.example.lib.WidgetUtil\n"))

    def test_ls_missing_artifact_and_version(self):
        code, out, err = self.run_cli("ls", "com.example.lib:widge", "--root", self.root)
        self.assertEqual((code, err), (1, "error: no artifact com.example.lib:widge "
                                          "(did you mean: com.example.lib:widget?)\n"))
        code, out, err = self.run_cli("ls", "com.example.lib:widget:9.9", "--root", self.root)
        self.assertEqual((code, err), (1, "error: no artifact com.example.lib:widget:9.9\n"))

    def test_unknown_flag_is_usage_error(self):
        code, out, err = self.run_cli("find", "x", "--bogus")
        self.assertEqual((code, out, err), (2, "", "unknown flag: --bogus\n"))

    def test_help_lists_every_flag(self):
        code, out, err = self.run_cli("--help")
        self.assertEqual((code, err), (0, ""))
        for token in ("find", "cat", "ls", "--root", "--lines"):
            self.assertIn(token, out)


if __name__ == "__main__":
    unittest.main()
