"""End-to-end tests for jarsrc. Run: python3 -m unittest discover tests"""

import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
JARSRC = os.path.join(HERE, "..", "jarsrc")

WIDGET_V1 = "package com.example.lib;\n\npublic class Widget {}\n"
WIDGET_V2 = "package com.example.lib;\r\n\r\npublic class Widget {   \r\n  int v = 2;\r\n}\r\n"


def make_jar(root, group, artifact, version, files):
    d = os.path.join(root, *group.split("."), artifact, version)
    os.makedirs(d, exist_ok=True)
    with zipfile.ZipFile(os.path.join(d, f"{artifact}-{version}-sources.jar"), "w") as zf:
        for name, body in files.items():
            zf.writestr(name, body)


class JarsrcTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        r = cls.root = cls.tmp.name
        for v in ("1.9.0", "1.10.0", "1.2"):
            make_jar(r, "com.example", "lib", v, {"com/example/lib/Widget.java": WIDGET_V1})
        make_jar(r, "com.example", "lib", "2.0.0-beta", {
            "com/example/lib/Widget.java": WIDGET_V2,
            "com/example/lib/Wrench.java": "class Wrench {}\n",
            "com/example/lib/internal/Gear.java": "class Gear {}\n",
            "META-INF/MANIFEST.MF": "x",
        })
        make_jar(r, "com.example", "lib", "2.0.0", {"com/example/lib/Widget.java": WIDGET_V1})
        make_jar(r, "org.other", "dup", "1.0", {"com/shared/Thing.java": "a\n"})
        make_jar(r, "org.other", "dup2", "1.0", {"com/shared/Thing.java": "b\n"})
        make_jar(r, "org.other", "dupx", "1.0", {"com/shared/Thing.java": "c\n"})

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_cli(self, *args, root=True):
        argv = [sys.executable, JARSRC, *args] + (["--root", self.root] if root else [])
        p = subprocess.run(argv, capture_output=True)
        for stream in (p.stdout, p.stderr):
            self.assertNotIn(b"\r", stream)
            for line in stream.decode().split("\n"):
                self.assertEqual(line, line.rstrip())
        return p.returncode, p.stdout.decode(), p.stderr.decode()

    def test_help(self):
        code, out, err = self.run_cli("--help", root=False)
        self.assertEqual((code, err), (0, ""))
        for word in ("find", "cat", "ls", "--root", "--lines", "--help"):
            self.assertIn(word, out)

    def test_unknown_flag(self):
        self.assertEqual(self.run_cli("find", "X", "--bogus"), (2, "", "unknown flag: --bogus\n"))

    def test_missing_arg_is_usage(self):
        self.assertEqual(self.run_cli("cat")[0], 2)
        self.assertEqual(self.run_cli()[0], 2)
        self.assertEqual(self.run_cli("cat", "X", "--lines", "abc")[0], 2)

    def test_find_exact_lists_all_versions(self):
        code, out, err = self.run_cli("find", "com.example.lib.Widget")
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(out, "".join(
            f"com.example.lib.Widget com.example:lib:{v}\n"
            for v in sorted(["1.9.0", "1.10.0", "1.2", "2.0.0-beta", "2.0.0"])))

    def test_find_globs(self):
        _, out, _ = self.run_cli("find", "com.example.lib.*")
        self.assertNotIn("Gear", out)
        self.assertIn("com.example.lib.Wrench com.example:lib:2.0.0-beta\n", out)
        _, out, _ = self.run_cli("find", "com.**.Gear")
        self.assertEqual(out, "com.example.lib.internal.Gear com.example:lib:2.0.0-beta\n")
        _, out, _ = self.run_cli("find", "com.*.Gear")
        self.assertEqual(out, "")

    def test_find_not_found_with_suggestions(self):
        self.assertEqual(self.run_cli("find", "com.example.W"),
                         (1, "", "error: no matches for com.example.W (did you mean: Widget, Wrench?)\n"))
        self.assertEqual(self.run_cli("find", "Zzz"), (1, "", "error: no matches for Zzz\n"))

    def test_cat_highest_version_normalized(self):
        # 2.0.0-beta > 2.0.0 per spec; its CRLF + trailing spaces are normalized.
        code, out, err = self.run_cli("cat", "com.example.lib.Widget")
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(out, "package com.example.lib;\n\npublic class Widget {\n  int v = 2;\n}\n")

    def test_cat_lines(self):
        self.assertEqual(self.run_cli("cat", "com.example.lib.Widget", "--lines", "3:4")[1],
                         "public class Widget {\n  int v = 2;\n")
        self.assertEqual(self.run_cli("cat", "com.example.lib.Widget", "--lines", "4:99")[1],
                         "  int v = 2;\n}\n")
        self.assertEqual(self.run_cli("cat", "com.example.lib.Widget", "--lines", "50:60"), (0, "", ""))

    def test_cat_ambiguous(self):
        self.assertEqual(self.run_cli("cat", "com.shared.Thing"), (2, "",
            "error: com.shared.Thing is ambiguous across: org.other:dup, org.other:dup2, org.other:dupx\n"))

    def test_cat_not_found(self):
        self.assertEqual(self.run_cli("cat", "x.T"),
                         (1, "", "error: no source for x.T (did you mean: Thing?)\n"))

    def test_ls_versions(self):
        self.assertEqual(self.run_cli("ls", "com.example:lib"),
                         (0, "1.2\n1.9.0\n1.10.0\n2.0.0\n2.0.0-beta\n", ""))

    def test_ls_version_contents(self):
        self.assertEqual(self.run_cli("ls", "com.example:lib:2.0.0-beta")[1],
                         "com.example.lib.Widget\ncom.example.lib.Wrench\ncom.example.lib.internal.Gear\n")

    def test_ls_not_found(self):
        self.assertEqual(self.run_cli("ls", "org.other:du"),
                         (1, "", "error: no artifact org.other:du (did you mean: org.other:dup, org.other:dup2, org.other:dupx?)\n"))
        self.assertEqual(self.run_cli("ls", "com.example:lib:9"), (1, "", "error: no artifact com.example:lib:9\n"))


if __name__ == "__main__":
    unittest.main()
