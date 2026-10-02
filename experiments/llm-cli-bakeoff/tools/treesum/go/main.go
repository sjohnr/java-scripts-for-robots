// treesum prints a compact repo overview: file/dir counts, by-extension
// stats, and largest files.
package main

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

const helpText = `usage: treesum [DIR] [--top N] [--ext-limit M]

DIR defaults to "." (current directory).

flags:
  --top N         number of largest files to list (default 10)
  --ext-limit M   max distinct extensions shown in the by-extension table (default 20)

Ignored directories (never descended into): .git, node_modules, build,
.gradle, target, dist, .venv, __pycache__, .idea, .vscode
`

var ignoreDirs = map[string]struct{}{
	".git": {}, "node_modules": {}, "build": {}, ".gradle": {}, "target": {},
	"dist": {}, ".venv": {}, "__pycache__": {}, ".idea": {}, ".vscode": {},
}

func main() {
	args := os.Args[1:]
	for _, a := range args {
		if a == "--help" {
			fmt.Print(helpText)
			os.Exit(0)
		}
	}

	dir := "."
	hasDir := false
	top := 10
	extLimit := 20

	i := 0
	for i < len(args) {
		a := args[i]
		switch {
		case a == "--top":
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "error: --top requires a value")
				os.Exit(2)
			}
			n, err := strconv.Atoi(args[i+1])
			if err != nil {
				fmt.Fprintf(os.Stderr, "error: invalid value for --top: %s\n", args[i+1])
				os.Exit(2)
			}
			top = n
			i += 2
		case a == "--ext-limit":
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "error: --ext-limit requires a value")
				os.Exit(2)
			}
			n, err := strconv.Atoi(args[i+1])
			if err != nil {
				fmt.Fprintf(os.Stderr, "error: invalid value for --ext-limit: %s\n", args[i+1])
				os.Exit(2)
			}
			extLimit = n
			i += 2
		case strings.HasPrefix(a, "--"):
			fmt.Fprintf(os.Stderr, "unknown flag: %s\n", a)
			os.Exit(2)
		default:
			if hasDir {
				fmt.Fprintf(os.Stderr, "unknown flag: %s\n", a)
				os.Exit(2)
			}
			dir = a
			hasDir = true
			i++
		}
	}

	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		fmt.Fprintf(os.Stderr, "error: cannot read %s\n", dir)
		os.Exit(2)
	}

	type fileRec struct {
		relPath string
		size    int64
		ext     string
		loc     int
	}

	var files []fileRec
	dirCount := 0
	extStats := map[string]struct {
		count int
		loc   int
	}{}

	var walk func(absDir, relDir string)
	walk = func(absDir, relDir string) {
		entries, err := os.ReadDir(absDir)
		if err != nil {
			return
		}
		// sort entries by name for deterministic traversal (output is
		// resorted anyway, but keeps behavior stable)
		sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
		for _, e := range entries {
			name := e.Name()
			absPath := filepath.Join(absDir, name)
			relPath := name
			if relDir != "" {
				relPath = relDir + "/" + name
			}

			// Determine if this entry is a symlink without following it.
			linfo, lerr := os.Lstat(absPath)
			if lerr != nil {
				continue
			}
			if linfo.Mode()&os.ModeSymlink != 0 {
				continue // symlinks are not followed and not counted
			}

			if e.IsDir() {
				if _, ignored := ignoreDirs[name]; ignored {
					continue
				}
				dirCount++
				walk(absPath, relPath)
				continue
			}

			if !e.Type().IsRegular() {
				continue
			}

			size := linfo.Size()
			ext := extOf(name)
			loc := countLOC(absPath)

			files = append(files, fileRec{relPath, size, ext, loc})
			st := extStats[ext]
			st.count++
			st.loc += loc
			extStats[ext] = st
		}
	}

	walk(dir, "")

	// --- by extension, with ext-limit folding ---
	type extRow struct {
		ext   string // sort key ("" for none)
		label string
		count int
		loc   int
	}
	var extRows []extRow
	for ext, st := range extStats {
		label := ext
		if label == "" {
			label = "(none)"
		}
		extRows = append(extRows, extRow{ext, label, st.count, st.loc})
	}
	sort.Slice(extRows, func(i, j int) bool {
		if extRows[i].count != extRows[j].count {
			return extRows[i].count > extRows[j].count
		}
		return extRows[i].ext < extRows[j].ext
	})

	var shown []extRow
	var otherCount, otherLoc int
	if extLimit < 0 {
		extLimit = 0
	}
	for idx, row := range extRows {
		if idx < extLimit {
			shown = append(shown, row)
		} else {
			otherCount += row.count
			otherLoc += row.loc
		}
	}
	if otherCount > 0 || otherLoc > 0 {
		if len(extRows) > extLimit {
			shown = append(shown, extRow{ext: "other", label: "other", count: otherCount, loc: otherLoc})
		}
	}

	// --- largest files ---
	sorted := make([]fileRec, len(files))
	copy(sorted, files)
	sort.Slice(sorted, func(i, j int) bool {
		if sorted[i].size != sorted[j].size {
			return sorted[i].size > sorted[j].size
		}
		return sorted[i].relPath < sorted[j].relPath
	})
	if top < 0 {
		top = 0
	}
	n := top
	if n > len(sorted) {
		n = len(sorted)
	}

	w := bufio.NewWriter(os.Stdout)
	defer w.Flush()

	fmt.Fprintf(w, "files: %d\n", len(files))
	fmt.Fprintf(w, "dirs: %d\n", dirCount)
	fmt.Fprintf(w, "by extension:\n")
	for _, row := range shown {
		fmt.Fprintf(w, "  %s  files=%d loc=%d\n", row.label, row.count, row.loc)
	}
	fmt.Fprintf(w, "largest files:\n")
	for _, f := range sorted[:n] {
		fmt.Fprintf(w, "  %d %s\n", f.size, f.relPath)
	}
}

func extOf(name string) string {
	i := strings.LastIndexByte(name, '.')
	if i <= 0 { // no dot, or dotfile with no other dot (leading dot at index 0)
		if i < 0 {
			return ""
		}
		// name starts with '.', check if there's another '.' later
		// (i==0 means the only dot is the leading one)
		return ""
	}
	return strings.ToLower(name[i+1:])
}

func countLOC(path string) int {
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()

	buf := make([]byte, 64*1024)
	newlines := 0
	nonEmpty := false
	lastByte := byte(0)
	for {
		n, err := f.Read(buf)
		if n > 0 {
			nonEmpty = true
			for _, b := range buf[:n] {
				if b == '\n' {
					newlines++
				}
			}
			lastByte = buf[n-1]
		}
		if err != nil {
			break
		}
	}
	loc := newlines
	if nonEmpty && lastByte != '\n' {
		loc++
	}
	return loc
}
