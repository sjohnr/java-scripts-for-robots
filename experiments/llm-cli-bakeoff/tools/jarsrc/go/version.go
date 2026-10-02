package main

import "strings"

// splitVersion splits a version string on '.' and '-' into segments.
func splitVersion(v string) []string {
	return strings.FieldsFunc(v, func(r rune) bool {
		return r == '.' || r == '-'
	})
}

// isNonNegInt reports whether s parses as a non-negative base-10 integer
// (optional leading zeros allowed, no leading '+', no sign at all).
func isNonNegInt(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// numericValue compares two numeric-string segments numerically, ignoring
// leading zeros, without overflowing for arbitrarily long digit strings.
func compareNumeric(a, b string) int {
	a = strings.TrimLeft(a, "0")
	b = strings.TrimLeft(b, "0")
	if len(a) != len(b) {
		if len(a) < len(b) {
			return -1
		}
		return 1
	}
	if a < b {
		return -1
	}
	if a > b {
		return 1
	}
	return 0
}

// compareVersions implements the numeric-segment version comparison
// algorithm from spec/00-conventions.md. Returns <0, 0, >0 as a < b, a == b,
// a > b respectively.
func compareVersions(av, bv string) int {
	as := splitVersion(av)
	bs := splitVersion(bv)
	n := len(as)
	if len(bs) < n {
		n = len(bs)
	}
	for i := 0; i < n; i++ {
		sa, sb := as[i], bs[i]
		if isNonNegInt(sa) && isNonNegInt(sb) {
			if c := compareNumeric(sa, sb); c != 0 {
				return c
			}
		} else {
			if sa < sb {
				return -1
			}
			if sa > sb {
				return 1
			}
		}
	}
	if len(as) != len(bs) {
		if len(as) < len(bs) {
			return -1
		}
		return 1
	}
	return 0
}
