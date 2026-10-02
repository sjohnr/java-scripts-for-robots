"""End-to-end tests for treesum. Run: python3 -m unittest -v test_treesum"""

import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TREESUM = os.path.join(HERE, "treesum")


def make_tree(base, files, dirs=(), symlinks=()):
    for d in dirs:
        os.makedirs(os.path.join(base, d), exist_ok=True)
    for rel, content in files.items():
        full = os.path.join(base, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        mode = "wb" if isinstance(content, bytes) else "w"
        with open(full, mode) as f:
            f.write(content)
    for link, target in symlinks:
        os.symlink(target, os.path.join(base, link))


def run_treesum(*args, cwd=None):
    return subprocess.run(
        [sys.executable, TREESUM, *args],
        capture_output=True,
        cwd=cwd,
    )


class TreesumTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.base = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def assertOutput(self, proc, expected, code=0):
        self.assertEqual(proc.returncode, code, proc.stderr)
        self.assertEqual(proc.stderr, b"")
        self.assertEqual(proc.stdout.decode("utf-8"), expected)

    def test_basic_summary(self):
        make_tree(self.base, {
            "a.py": "x = 1\ny = 2\n",     # 2 loc, 12 bytes
            "src/b.py": "print(1)",       # 1 loc (no trailing newline), 8 bytes
            "README": "hi\n",             # (none), 1 loc, 3 bytes
        }, dirs=["empty"])
        self.assertOutput(run_treesum(self.base), (
            "files: 3\n"
            "dirs: 2\n"
            "by extension:\n"
            "  py  files=2 loc=3\n"
            "  (none)  files=1 loc=1\n"
            "largest files:\n"
            "  12 a.py\n"
            "  8 src/b.py\n"
            "  3 README\n"
        ))

    def test_default_dir_is_cwd(self):
        make_tree(self.base, {"x.txt": "a\n"})
        proc = run_treesum(cwd=self.base)
        self.assertOutput(proc, (
            "files: 1\n"
            "dirs: 0\n"
            "by extension:\n"
            "  txt  files=1 loc=1\n"
            "largest files:\n"
            "  2 x.txt\n"
        ))

    def test_empty_directory(self):
        self.assertOutput(run_treesum(self.base), (
            "files: 0\n"
            "dirs: 0\n"
            "by extension:\n"
            "largest files:\n"
        ))

    def test_ignored_directories_not_descended(self):
        make_tree(self.base, {
            "keep.c": "int x;\n",
            ".git/config": "x\n",
            "node_modules/pkg/index.js": "y\n",
            "sub/build/out.o": "z\n",
            "sub/.venv/lib.py": "w\n",
            "sub/real.c": "int y;\n",
        })
        self.assertOutput(run_treesum(self.base), (
            "files: 2\n"
            "dirs: 1\n"
            "by extension:\n"
            "  c  files=2 loc=2\n"
            "largest files:\n"
            "  7 keep.c\n"
            "  7 sub/real.c\n"
        ))

    def test_ignored_name_as_file_is_counted(self):
        make_tree(self.base, {"build": "abc"})
        self.assertOutput(run_treesum(self.base), (
            "files: 1\n"
            "dirs: 0\n"
            "by extension:\n"
            "  (none)  files=1 loc=1\n"
            "largest files:\n"
            "  3 build\n"
        ))

    def test_root_named_like_ignored_dir_is_scanned(self):
        root = os.path.join(self.base, "target")
        make_tree(root, {"f.txt": "a\n"})
        self.assertOutput(run_treesum(root), (
            "files: 1\n"
            "dirs: 0\n"
            "by extension:\n"
            "  txt  files=1 loc=1\n"
            "largest files:\n"
            "  2 f.txt\n"
        ))

    def test_symlinks_not_followed_or_counted(self):
        make_tree(self.base, {"real/inside.txt": "a\n", "plain.txt": "b\n"},
                  symlinks=[("link_file", "plain.txt"), ("link_dir", "real")])
        self.assertOutput(run_treesum(self.base), (
            "files: 2\n"
            "dirs: 1\n"
            "by extension:\n"
            "  txt  files=2 loc=2\n"
            "largest files:\n"
            "  2 plain.txt\n"
            "  2 real/inside.txt\n"
        ))

    def test_extension_rules(self):
        make_tree(self.base, {
            ".gitignore": "a\n",    # (none)
            "a.": "a\n",            # trailing dot -> (none)
            "Makefile": "a\n",      # (none)
            "X.TXT": "a\n",         # txt (lowercased)
            ".a.b": "a\n",          # b
            "archive.tar.GZ": "a\n",  # gz
        })
        self.assertOutput(run_treesum(self.base), (
            "files: 6\n"
            "dirs: 0\n"
            "by extension:\n"
            "  (none)  files=3 loc=3\n"
            "  b  files=1 loc=1\n"
            "  gz  files=1 loc=1\n"
            "  txt  files=1 loc=1\n"
            "largest files:\n"
            "  2 .a.b\n"
            "  2 .gitignore\n"
            "  2 Makefile\n"
            "  2 X.TXT\n"
            "  2 a.\n"
            "  2 archive.tar.GZ\n"
        ))

    def test_loc_counting(self):
        cases = {
            "empty.t": b"",          # 0
            "one.t": b"abc",         # 1 (no trailing newline)
            "nl.t": b"a\n",          # 1
            "two.t": b"a\nb",        # 2
            "blank.t": b"\n\n",      # 2
        }
        make_tree(self.base, cases)
        self.assertOutput(run_treesum(self.base, "--top", "0"), (
            "files: 5\n"
            "dirs: 0\n"
            "by extension:\n"
            "  t  files=5 loc=6\n"
            "largest files:\n"
        ))

    def test_binary_file_counted_by_newlines(self):
        make_tree(self.base, {"bin.dat": b"\x00\xff\n\x01"})
        self.assertOutput(run_treesum(self.base), (
            "files: 1\n"
            "dirs: 0\n"
            "by extension:\n"
            "  dat  files=1 loc=2\n"
            "largest files:\n"
            "  4 bin.dat\n"
        ))

    def test_extension_sort_ties(self):
        # Equal counts: "(none)" sorts as "" so it precedes lettered extensions.
        make_tree(self.base, {"a.zz": "", "b.aa": "", "noext": ""})
        self.assertOutput(run_treesum(self.base), (
            "files: 3\n"
            "dirs: 0\n"
            "by extension:\n"
            "  (none)  files=1 loc=0\n"
            "  aa  files=1 loc=0\n"
            "  zz  files=1 loc=0\n"
            "largest files:\n"
            "  0 a.zz\n"
            "  0 b.aa\n"
            "  0 noext\n"
        ))

    def test_extension_sort_by_count(self):
        make_tree(self.base, {"a.x": "", "b.y": "", "c.y": "", "d": ""})
        out = run_treesum(self.base).stdout.decode()
        rows = [l for l in out.splitlines() if l.startswith("  ") and "files=" in l]
        self.assertEqual(rows, [
            "  y  files=2 loc=0",
            "  (none)  files=1 loc=0",
            "  x  files=1 loc=0",
        ])

    def test_ext_limit_folds_into_other_last(self):
        make_tree(self.base, {
            "a.a": "x\n", "b.a": "x\n", "c.a": "x\n",   # a: 3 files, 3 loc
            "d.b": "x\n", "e.b": "x\n",                 # b: 2 files, 2 loc
            "f.c": "x\n",                               # c: 1 file, 1 loc
            "g.d": "x\n",                               # d: 1 file, 1 loc
        })
        self.assertOutput(run_treesum(self.base, "--ext-limit", "2", "--top", "0"), (
            "files: 7\n"
            "dirs: 0\n"
            "by extension:\n"
            "  a  files=3 loc=3\n"
            "  b  files=2 loc=2\n"
            "  other  files=2 loc=2\n"
            "largest files:\n"
        ))

    def test_ext_limit_zero_folds_everything(self):
        make_tree(self.base, {"a.a": "x\n", "b": "y\n"})
        self.assertOutput(run_treesum(self.base, "--ext-limit", "0", "--top", "0"), (
            "files: 2\n"
            "dirs: 0\n"
            "by extension:\n"
            "  other  files=2 loc=2\n"
            "largest files:\n"
        ))

    def test_no_other_row_when_within_limit(self):
        make_tree(self.base, {"a.a": "x\n"})
        out = run_treesum(self.base, "--ext-limit", "1").stdout.decode()
        self.assertNotIn("other", out)

    def test_top_and_size_ties(self):
        make_tree(self.base, {
            "z.txt": "12345",
            "m/b.txt": "12345",
            "m/a.txt": "12345",
            "small.txt": "1",
            "big.txt": "1234567890",
        })
        self.assertOutput(run_treesum(self.base, "--top", "4"), (
            "files: 5\n"
            "dirs: 1\n"
            "by extension:\n"
            "  txt  files=5 loc=5\n"
            "largest files:\n"
            "  10 big.txt\n"
            "  5 m/a.txt\n"
            "  5 m/b.txt\n"
            "  5 z.txt\n"
        ))

    def test_top_equals_form(self):
        make_tree(self.base, {"a.a": "1", "b.a": "22"})
        out = run_treesum(self.base, "--top=1").stdout.decode()
        self.assertTrue(out.endswith("largest files:\n  2 b.a\n"), out)

    def test_help_lists_flags_and_ignores(self):
        proc = run_treesum("--help")
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(proc.stderr, b"")
        text = proc.stdout.decode()
        for needle in ["DIR", "--top", "--ext-limit", "--help", ".git",
                       "node_modules", "build", ".gradle", "target", "dist",
                       ".venv", "__pycache__", ".idea", ".vscode"]:
            self.assertIn(needle, text)

    def test_unknown_flag(self):
        proc = run_treesum("--bogus")
        self.assertEqual(proc.returncode, 2)
        self.assertEqual(proc.stdout, b"")
        self.assertEqual(proc.stderr, b"unknown flag: --bogus\n")

    def test_bad_count_is_usage_error(self):
        for args in (["--top", "abc"], ["--top", "-1"], ["--ext-limit", "1.5"],
                     ["--top", ""], ["--top"]):
            proc = run_treesum(*args)
            self.assertEqual(proc.returncode, 2, args)
            self.assertEqual(proc.stdout, b"", args)
            self.assertTrue(proc.stderr.startswith(b"error: "), args)

    def test_too_many_positionals(self):
        proc = run_treesum(self.base, self.base)
        self.assertEqual(proc.returncode, 2)
        self.assertEqual(proc.stdout, b"")
        self.assertTrue(proc.stderr.startswith(b"error: "))

    def test_missing_dir(self):
        missing = os.path.join(self.base, "nope")
        proc = run_treesum(missing)
        self.assertEqual(proc.returncode, 2)
        self.assertEqual(proc.stdout, b"")
        self.assertEqual(proc.stderr, f"error: cannot read {missing}\n".encode())

    def test_dir_is_a_file(self):
        make_tree(self.base, {"f.txt": "a\n"})
        path = os.path.join(self.base, "f.txt")
        proc = run_treesum(path)
        self.assertEqual(proc.returncode, 2)
        self.assertEqual(proc.stdout, b"")
        self.assertEqual(proc.stderr, f"error: cannot read {path}\n".encode())

    def test_output_is_lf_and_no_trailing_whitespace(self):
        make_tree(self.base, {"a.py": "x\n", "sub/b.py": "y\n"})
        out = run_treesum(self.base).stdout
        self.assertNotIn(b"\r", out)
        for line in out.split(b"\n")[:-1]:
            self.assertEqual(line, line.rstrip(b" \t"))


if __name__ == "__main__":
    unittest.main()
