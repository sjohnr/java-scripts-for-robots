"""Tests for jarsrc. Run: python3 -m unittest -v test_jarsrc"""

import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
JARSRC = os.path.join(HERE, "jarsrc")


def make_jar(root, group, artifact, version, files):
    d = os.path.join(root, *group.split("."), artifact, version)
    os.makedirs(d, exist_ok=True)
    with zipfile.ZipFile(os.path.join(d, f"{artifact}-{version}-sources.jar"), "w") as zf:
        for name, content in files.items():
            zf.writestr(name, content)


class JarsrcTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = cls.root = cls.tmp.name
        for v in ("1.9.0", "1.10.0", "1.2"):
            make_jar(root, "com.example.lib", "widget", v, {
                "com/example/lib/Widget.java": f"// {v}\nline2\nline3   \r\nline4\n",
                "com/example/lib/WidgetFactory.java": f"// {v}\nline2  \r\nline3\n",
            })
        make_jar(root, "com.example.lib", "widget", "1.10.0-beta", {
            "com/example/lib/Widget.java": "// beta\n",
            "META-INF/MANIFEST.MF": "",
            "com/example/lib/": "",
        })
        make_jar(root, "com.example.lib", "widget", "1.0.0", {
            "com/example/lib/OldOnly.java": "old\n",
        })
        make_jar(root, "org.other", "dup", "2.0", {"com/example/lib/Shared.java": "a\n"})
        make_jar(root, "com.example.lib", "widget-extra", "0.1", {
            "com/example/lib/Shared.java": "b\n",
            "com/example/lib/sub/Deep.java": "d\n",
        })

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_cli(self, *args, root=True):
        argv = [sys.executable, JARSRC, *args]
        if root:
            argv += ["--root", self.root]
        p = subprocess.run(argv, capture_output=True)
        return p.returncode, p.stdout.decode(), p.stderr.decode()

    def assertCli(self, args, code, stdout="", stderr="", root=True):
        self.assertEqual(self.run_cli(*args, root=root), (code, stdout, stderr))

    # find
    def test_find_exact_lists_all_versions(self):
        self.assertCli(["find", "com.example.lib.Widget"], 0,
                       "com.example.lib.Widget com.example.lib:widget:1.10.0\n"
                       "com.example.lib.Widget com.example.lib:widget:1.10.0-beta\n"
                       "com.example.lib.Widget com.example.lib:widget:1.2\n"
                       "com.example.lib.Widget com.example.lib:widget:1.9.0\n")

    def test_find_single_star_does_not_cross_dots(self):
        code, out, _ = self.run_cli("find", "com.example.lib.*")
        self.assertEqual(code, 0)
        self.assertNotIn("Deep", out)
        self.assertIn("com.example.lib.Shared com.example.lib:widget-extra:0.1\n", out)

    def test_find_double_star(self):
        self.assertCli(["find", "**.Deep"], 0,
                       "com.example.lib.sub.Deep com.example.lib:widget-extra:0.1\n")

    def test_find_no_match_with_suggestions(self):
        self.assertCli(["find", "com.x.Wid"], 1, "",
                       "error: no matches for com.x.Wid (did you mean: Widget, WidgetFactory?)\n")

    def test_find_no_match_without_suggestions(self):
        self.assertCli(["find", "Zzz"], 1, "", "error: no matches for Zzz\n")

    # cat
    def test_cat_highest_version_and_normalizes(self):
        self.assertCli(["cat", "com.example.lib.Widget"], 0, "// beta\n")
        self.assertCli(["cat", "com.example.lib.WidgetFactory"], 0,
                       "// 1.10.0\nline2\nline3\n")

    def test_cat_lines(self):
        self.assertCli(["cat", "com.example.lib.WidgetFactory", "--lines", "2:3"], 0, "line2\nline3\n")
        self.assertCli(["cat", "com.example.lib.OldOnly", "--lines", "0:99"], 0, "old\n")
        self.assertCli(["cat", "com.example.lib.OldOnly", "--lines", "5:9"], 0, "")

    def test_cat_ambiguous(self):
        self.assertCli(["cat", "com.example.lib.Shared"], 2, "",
                       "error: com.example.lib.Shared is ambiguous across: "
                       "com.example.lib:widget-extra, org.other:dup\n")

    def test_cat_not_found(self):
        self.assertCli(["cat", "a.b.Old"], 1, "",
                       "error: no source for a.b.Old (did you mean: OldOnly?)\n")

    def test_cat_bad_lines(self):
        code, out, err = self.run_cli("cat", "com.example.lib.OldOnly", "--lines", "x")
        self.assertEqual((code, out), (2, ""))

    # ls
    def test_ls_versions(self):
        self.assertCli(["ls", "com.example.lib:widget"], 0,
                       "1.0.0\n1.2\n1.9.0\n1.10.0\n1.10.0-beta\n")

    def test_ls_version_classes(self):
        self.assertCli(["ls", "com.example.lib:widget:1.9.0"], 0,
                       "com.example.lib.Widget\ncom.example.lib.WidgetFactory\n")

    def test_ls_missing_artifact(self):
        self.assertCli(["ls", "com.example.lib:wid"], 1, "",
                       "error: no artifact com.example.lib:wid "
                       "(did you mean: com.example.lib:widget, com.example.lib:widget-extra?)\n")
        self.assertCli(["ls", "nope:nope"], 1, "", "error: no artifact nope:nope\n")

    def test_ls_missing_version(self):
        code, _, err = self.run_cli("ls", "com.example.lib:widget:9")
        self.assertEqual(code, 1)
        self.assertTrue(err.startswith("error: no artifact com.example.lib:widget:9"))

    # usage
    def test_help(self):
        code, out, err = self.run_cli("--help", root=False)
        self.assertEqual((code, err), (0, ""))
        for word in ("find", "cat", "ls", "--root", "--lines", "--help"):
            self.assertIn(word, out)
        self.assertFalse(any(l != l.rstrip() for l in out.split("\n")))

    def test_unknown_flag(self):
        self.assertCli(["find", "X", "--bogus"], 2, "", "unknown flag: --bogus\n")

    def test_missing_argument(self):
        code, out, _ = self.run_cli("find")
        self.assertEqual((code, out), (2, ""))


class VersionCompareTest(unittest.TestCase):
    def test_examples(self):
        import importlib.machinery, importlib.util
        loader = importlib.machinery.SourceFileLoader("jarsrc_mod", JARSRC)
        spec = importlib.util.spec_from_loader("jarsrc_mod", loader)
        mod = importlib.util.module_from_spec(spec)
        loader.exec_module(mod)
        c = mod.compare_versions
        self.assertEqual(c("1.2.0", "1.2"), 1)
        self.assertEqual(c("1.10.0", "1.9.0"), 1)
        self.assertEqual(c("1.0.0-beta", "1.0.0"), 1)
        self.assertEqual(c("01.2", "1.2"), 0)
        self.assertEqual(c("1.a", "1.B"), 1)
        self.assertEqual(c("1.2", "1.a"), -1)


if __name__ == "__main__":
    unittest.main()
