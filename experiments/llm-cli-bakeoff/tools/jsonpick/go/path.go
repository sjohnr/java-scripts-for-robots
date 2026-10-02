package main

import "strconv"

type pathSeg struct {
	isIndex bool
	key     string
	idx     int
}

// parsePath parses PATH syntax: a.b[0].c, a.b[0][1].c, leading '.' or empty
// selects the document root.
func parsePath(path string) []pathSeg {
	if path == "" || path == "." {
		return nil
	}
	s := path
	if len(s) > 0 && s[0] == '.' {
		s = s[1:]
	}
	var segs []pathSeg
	i := 0
	for i < len(s) {
		if s[i] == '[' {
			j := i + 1
			for j < len(s) && s[j] != ']' {
				j++
			}
			idxStr := s[i+1 : j]
			idx, err := strconv.Atoi(idxStr)
			if err != nil {
				idx = -1 // will fail to resolve; treated as not-found
			}
			segs = append(segs, pathSeg{isIndex: true, idx: idx})
			i = j + 1
			if i < len(s) && s[i] == '.' {
				i++
			}
		} else {
			start := i
			for i < len(s) && s[i] != '.' && s[i] != '[' {
				i++
			}
			segs = append(segs, pathSeg{isIndex: false, key: s[start:i]})
			if i < len(s) && s[i] == '.' {
				i++
			}
		}
	}
	return segs
}

func navigate(root *Value, segs []pathSeg) (*Value, bool) {
	cur := root
	for _, sg := range segs {
		if sg.isIndex {
			if cur.Kind != KArray {
				return nil, false
			}
			if sg.idx < 0 || sg.idx >= len(cur.Arr) {
				return nil, false
			}
			cur = cur.Arr[sg.idx]
		} else {
			if cur.Kind != KObject {
				return nil, false
			}
			found := false
			for _, e := range cur.Obj {
				if e.Key == sg.key {
					cur = e.Val
					found = true
					break
				}
			}
			if !found {
				return nil, false
			}
		}
	}
	return cur, true
}
