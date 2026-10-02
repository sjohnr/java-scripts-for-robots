import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TREESUM = os.path.join(HERE, "treesum")


def run(*args, cwd=None):
    return subprocess.run(
        [sys.executable, TREESUM, *args],
        cwd=cwd,
        capture_output=True,
    )


def write(root, rel, data):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


class TreesumTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="treesum-test-")
        self.addCleanup(shutil.rmtree, self.root, True)
        write(self.root, "a.py", b"x\ny\n")          # 4 bytes, loc 2
        write(self.root, "b.PY", b"hello")           # 5 bytes, loc 1 (no final newline)
        write(self.root, "README", b"one\n")         # 4 bytes, loc 1, ext (none)
        write(self.root, ".gitignore", b"*.pyc\n")   # 6 bytes, loc 1, ext (none)
        write(self.root, "empty.txt", b"")           # 0 bytes, loc 0
        write(self.root, "sub/c.md", b"# t\n\n")     # 5 bytes, loc 2
        write(self.root, "sub/deep/z.py", b"a\n")    # 2 bytes, loc 1
        write(self.root, "build/ignored.txt", b"zzz\n")
        write(self.root, "sub/node_modules/x.js", b"zzz\n")
        os.symlink(os.path.join(self.root, "a.py"), os.path.join(self.root, "link.py"))

    def test_full_report(self):
        p = run(self.root)
        self.assertEqual(p.returncode, 0)
        self.assertEqual(p.stderr, b"")
        self.assertEqual(
            p.stdout.decode("utf-8"),
            "files: 7\n"
            "dirs: 2\n"
            "by extension:\n"
            "  py  files=3 loc=4\n"
            "  (none)  files=2 loc=2\n"
            "  md  files=1 loc=2\n"
            "  txt  files=1 loc=0\n"
            "largest files:\n"
            "  6 .gitignore\n"
            "  5 b.PY\n"
            "  5 sub/c.md\n"
            "  4 README\n"
            "  4 a.py\n"
            "  2 sub/deep/z.py\n"
            "  0 empty.txt\n",
        )

    def test_top_limits_largest(self):
        p = run(self.root, "--top", "2")
        self.assertEqual(p.returncode, 0)
        self.assertTrue(p.stdout.endswith(b"largest files:\n  6 .gitignore\n  5 b.PY\n"))

    def test_top_zero_keeps_header(self):
        p = run(self.root, "--top=0")
        self.assertTrue(p.stdout.endswith(b"largest files:\n"))

    def test_ext_limit_folds_into_other(self):
        p = run(self.root, "--ext-limit", "2")
        self.assertEqual(p.returncode, 0)
        self.assertIn(
            b"by extension:\n  py  files=3 loc=4\n  (none)  files=2 loc=2\n"
            b"  other  files=2 loc=2\nlargest files:\n",
            p.stdout,
        )

    def test_default_dir_is_cwd(self):
        p = run(cwd=self.root)
        self.assertEqual(p.returncode, 0)
        self.assertTrue(p.stdout.startswith(b"files: 7\n"))

    def test_output_is_lf_only_with_no_trailing_spaces(self):
        p = run(self.root)
        self.assertNotIn(b"\r", p.stdout)
        for line in p.stdout.split(b"\n"):
            self.assertEqual(line, line.rstrip(b" \t"))

    def test_help(self):
        p = run("--help")
        self.assertEqual(p.returncode, 0)
        self.assertEqual(p.stderr, b"")
        for word in (b"DIR", b"--top", b"--ext-limit", b"node_modules", b".vscode"):
            self.assertIn(word, p.stdout)

    def test_unknown_flag(self):
        p = run("--bogus")
        self.assertEqual(p.returncode, 2)
        self.assertEqual(p.stdout, b"")
        self.assertEqual(p.stderr, b"unknown flag: --bogus\n")

    def test_missing_dir(self):
        missing = os.path.join(self.root, "nope")
        p = run(missing)
        self.assertEqual(p.returncode, 2)
        self.assertEqual(p.stdout, b"")
        self.assertEqual(p.stderr, f"error: cannot read {missing}\n".encode())

    def test_dir_is_a_file(self):
        p = run(os.path.join(self.root, "a.py"))
        self.assertEqual(p.returncode, 2)
        self.assertEqual(p.stdout, b"")

    def test_bad_number_is_usage_error(self):
        for args in (["--top", "abc"], ["--top", "-1"], ["--ext-limit="], ["--top"]):
            p = run(*args)
            self.assertEqual(p.returncode, 2, args)
            self.assertEqual(p.stdout, b"", args)

    def test_extra_positional_is_usage_error(self):
        p = run(self.root, self.root)
        self.assertEqual(p.returncode, 2)
        self.assertEqual(p.stdout, b"")


if __name__ == "__main__":
    unittest.main()
