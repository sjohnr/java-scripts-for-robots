// jarsrc queries a Gradle-cache-like tree of *-sources.jar files.
package main

import (
	"archive/zip"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

const helpText = `usage:
  jarsrc find <fqcn-or-glob> [--root DIR]
  jarsrc cat <fqcn> [--lines A:B] [--root DIR]
  jarsrc ls <group:artifact[:version]> [--root DIR]
  jarsrc --help

flags:
  --root DIR    root of the sources-jar tree (default: .)
  --lines A:B   (cat only) 1-indexed inclusive line range
`

func main() {
	args := os.Args[1:]

	for _, a := range args {
		if a == "--help" {
			fmt.Print(helpText)
			os.Exit(0)
		}
	}

	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "error: missing subcommand")
		os.Exit(2)
	}

	sub := args[0]
	rest := args[1:]

	switch sub {
	case "find":
		cmdFind(rest)
	case "cat":
		cmdCat(rest)
	case "ls":
		cmdLs(rest)
	default:
		fmt.Fprintf(os.Stderr, "error: unknown subcommand: %s\n", sub)
		os.Exit(2)
	}
}

// ---- shared GAV discovery ----

type gav struct {
	group, artifact, version, path string
}

func discover(root string) []gav {
	var out []gav
	filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // treat unreadable entries as absent
		}
		if d.IsDir() {
			return nil
		}
		if d.Type()&os.ModeSymlink != 0 {
			return nil
		}
		if !strings.HasSuffix(d.Name(), "-sources.jar") {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return nil
		}
		parts := strings.Split(filepath.ToSlash(rel), "/")
		n := len(parts)
		if n < 3 {
			return nil
		}
		version := parts[n-2]
		artifact := parts[n-3]
		group := strings.Join(parts[:n-3], ".")
		expected := artifact + "-" + version + "-sources.jar"
		if d.Name() != expected {
			return nil
		}
		out = append(out, gav{group, artifact, version, path})
		return nil
	})
	return out
}

func javaEntries(jarPath string) []string {
	r, err := zip.OpenReader(jarPath)
	if err != nil {
		return nil
	}
	defer r.Close()
	var out []string
	for _, f := range r.File {
		if strings.HasSuffix(f.Name, ".java") {
			fqcn := strings.ReplaceAll(strings.TrimSuffix(f.Name, ".java"), "/", ".")
			out = append(out, fqcn)
		}
	}
	return out
}

func readJavaSource(jarPath, fqcn string) (string, bool) {
	entryName := strings.ReplaceAll(fqcn, ".", "/") + ".java"
	r, err := zip.OpenReader(jarPath)
	if err != nil {
		return "", false
	}
	defer r.Close()
	for _, f := range r.File {
		if f.Name == entryName {
			rc, err := f.Open()
			if err != nil {
				return "", false
			}
			defer rc.Close()
			b, err := io.ReadAll(rc)
			if err != nil {
				return "", false
			}
			return string(b), true
		}
	}
	return "", false
}

func simpleName(fqcn string) string {
	if i := strings.LastIndexByte(fqcn, '.'); i >= 0 {
		return fqcn[i+1:]
	}
	return fqcn
}

// ---- suggestions ----

func suggest(query string, known map[string]struct{}) []string {
	var keys []string
	for k := range known {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var matches []string
	for _, k := range keys {
		if strings.HasPrefix(k, query) {
			matches = append(matches, k)
			if len(matches) == 3 {
				break
			}
		}
	}
	return matches
}

func printNotFound(what string, suggestions []string) {
	msg := "error: " + what
	if len(suggestions) > 0 {
		msg += " (did you mean: " + strings.Join(suggestions, ", ") + "?)"
	}
	fmt.Fprintln(os.Stderr, msg)
}

// ---- glob matching ----

func buildGlobRegex(pattern string) *regexp.Regexp {
	var sb strings.Builder
	sb.WriteString("^")
	i := 0
	for i < len(pattern) {
		if pattern[i] == '*' {
			if i+1 < len(pattern) && pattern[i+1] == '*' {
				sb.WriteString(".*")
				i += 2
			} else {
				sb.WriteString("[^.]*")
				i++
			}
		} else {
			j := i
			for j < len(pattern) && pattern[j] != '*' {
				j++
			}
			sb.WriteString(regexp.QuoteMeta(pattern[i:j]))
			i = j
		}
	}
	sb.WriteString("$")
	return regexp.MustCompile(sb.String())
}

// ---- flag parsing helpers ----

type parsedFlags struct {
	root      string
	lines     string
	hasLines  bool
	positional []string
}

func parseFlags(args []string, allowLines bool) parsedFlags {
	pf := parsedFlags{root: "."}
	i := 0
	for i < len(args) {
		a := args[i]
		switch {
		case a == "--root":
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "error: --root requires a value")
				os.Exit(2)
			}
			pf.root = args[i+1]
			i += 2
		case a == "--lines" && allowLines:
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "error: --lines requires a value")
				os.Exit(2)
			}
			pf.lines = args[i+1]
			pf.hasLines = true
			i += 2
		case strings.HasPrefix(a, "--"):
			fmt.Fprintf(os.Stderr, "unknown flag: %s\n", a)
			os.Exit(2)
		default:
			pf.positional = append(pf.positional, a)
			i++
		}
	}
	return pf
}

// ---- find ----

func cmdFind(args []string) {
	pf := parseFlags(args, false)
	if len(pf.positional) < 1 {
		fmt.Fprintln(os.Stderr, "error: missing argument: fqcn-or-glob")
		os.Exit(2)
	}
	pattern := pf.positional[0]

	gavs := discover(pf.root)
	re := buildGlobRegex(pattern)

	type m struct{ fqcn, gav string }
	var matches []m
	knownSimple := map[string]struct{}{}

	for _, g := range gavs {
		for _, fqcn := range javaEntries(g.path) {
			knownSimple[simpleName(fqcn)] = struct{}{}
			if re.MatchString(fqcn) {
				matches = append(matches, m{fqcn, g.group + ":" + g.artifact + ":" + g.version})
			}
		}
	}

	if len(matches) == 0 {
		printNotFound("no matches for "+pattern, suggest(simpleName(pattern), knownSimple))
		os.Exit(1)
	}

	sort.Slice(matches, func(i, j int) bool {
		if matches[i].fqcn != matches[j].fqcn {
			return matches[i].fqcn < matches[j].fqcn
		}
		return matches[i].gav < matches[j].gav
	})

	for _, mm := range matches {
		fmt.Printf("%s %s\n", mm.fqcn, mm.gav)
	}
}

// ---- cat ----

func cmdCat(args []string) {
	pf := parseFlags(args, true)
	if len(pf.positional) < 1 {
		fmt.Fprintln(os.Stderr, "error: missing argument: fqcn")
		os.Exit(2)
	}
	fqcn := pf.positional[0]

	gavs := discover(pf.root)

	type verInfo struct{ version, path string }
	gaVersions := map[string][]verInfo{}
	knownSimple := map[string]struct{}{}

	for _, g := range gavs {
		for _, e := range javaEntries(g.path) {
			knownSimple[simpleName(e)] = struct{}{}
			if e == fqcn {
				key := g.group + ":" + g.artifact
				gaVersions[key] = append(gaVersions[key], verInfo{g.version, g.path})
			}
		}
	}

	if len(gaVersions) == 0 {
		printNotFound("no source for "+fqcn, suggest(simpleName(fqcn), knownSimple))
		os.Exit(1)
	}

	if len(gaVersions) > 1 {
		var gas []string
		for k := range gaVersions {
			gas = append(gas, k)
		}
		sort.Strings(gas)
		fmt.Fprintf(os.Stderr, "error: %s is ambiguous across: %s\n", fqcn, strings.Join(gas, ", "))
		os.Exit(2)
	}

	var only []verInfo
	for _, v := range gaVersions {
		only = v
	}
	best := only[0]
	for _, v := range only[1:] {
		if compareVersions(v.version, best.version) > 0 {
			best = v
		}
	}

	src, ok := readJavaSource(best.path, fqcn)
	if !ok {
		// should not happen given discovery above
		printNotFound("no source for "+fqcn, nil)
		os.Exit(1)
	}

	if !pf.hasLines {
		os.Stdout.WriteString(src)
		return
	}

	rawLines := strings.Split(src, "\n")
	if len(rawLines) > 0 && rawLines[len(rawLines)-1] == "" {
		rawLines = rawLines[:len(rawLines)-1]
	}
	total := len(rawLines)

	a, b, err := parseLineRange(pf.lines)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: invalid value for --lines: %s\n", pf.lines)
		os.Exit(2)
	}

	if a > total || total == 0 {
		return
	}
	if a < 1 {
		a = 1
	}
	if b > total {
		b = total
	}
	if b < a {
		return
	}
	for _, line := range rawLines[a-1 : b] {
		fmt.Println(line)
	}
}

func parseLineRange(s string) (int, int, error) {
	parts := strings.SplitN(s, ":", 2)
	if len(parts) != 2 {
		return 0, 0, fmt.Errorf("bad range")
	}
	a, err := strconv.Atoi(parts[0])
	if err != nil {
		return 0, 0, err
	}
	b, err := strconv.Atoi(parts[1])
	if err != nil {
		return 0, 0, err
	}
	return a, b, nil
}

// ---- ls ----

func cmdLs(args []string) {
	pf := parseFlags(args, false)
	if len(pf.positional) < 1 {
		fmt.Fprintln(os.Stderr, "error: missing argument: group:artifact[:version]")
		os.Exit(2)
	}
	query := pf.positional[0]
	parts := strings.Split(query, ":")
	if len(parts) < 2 || len(parts) > 3 {
		fmt.Fprintf(os.Stderr, "error: invalid group:artifact[:version]: %s\n", query)
		os.Exit(2)
	}
	group, artifact := parts[0], parts[1]
	var version string
	versionGiven := len(parts) == 3
	if versionGiven {
		version = parts[2]
	}
	ga := group + ":" + artifact

	gavs := discover(pf.root)
	knownGA := map[string]struct{}{}
	var matchingGA []gav
	for _, g := range gavs {
		knownGA[g.group+":"+g.artifact] = struct{}{}
		if g.group == group && g.artifact == artifact {
			matchingGA = append(matchingGA, g)
		}
	}

	if len(matchingGA) == 0 {
		printNotFound("no artifact "+ga, suggest(ga, knownGA))
		os.Exit(1)
	}

	if !versionGiven {
		verSet := map[string]struct{}{}
		for _, g := range matchingGA {
			verSet[g.version] = struct{}{}
		}
		var vers []string
		for v := range verSet {
			vers = append(vers, v)
		}
		sort.Slice(vers, func(i, j int) bool {
			return compareVersions(vers[i], vers[j]) < 0
		})
		for _, v := range vers {
			fmt.Println(v)
		}
		return
	}

	var match *gav
	for i := range matchingGA {
		if matchingGA[i].version == version {
			match = &matchingGA[i]
			break
		}
	}
	if match == nil {
		printNotFound("no artifact "+ga+":"+version, suggest(ga, knownGA))
		os.Exit(1)
	}

	entries := javaEntries(match.path)
	sort.Strings(entries)
	for _, e := range entries {
		fmt.Println(e)
	}
}
