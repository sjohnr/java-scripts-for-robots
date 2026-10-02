package main

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// Value is a hand-rolled, order-preserving JSON value tree.
type Kind byte

const (
	KString Kind = iota
	KNumber
	KBool
	KNull
	KArray
	KObject
)

type ObjEntry struct {
	Key string
	Val *Value
}

type Value struct {
	Kind Kind
	Str  string
	Num  float64
	Bool bool
	Arr  []*Value
	Obj  []ObjEntry
}

// ---- parser ----

type parseError struct {
	offset int
	msg    string
}

func (e *parseError) Error() string { return e.msg }

type parser struct {
	data []byte
	pos  int
}

func lineCol(data []byte, offset int) (int, int) {
	line := 1
	col := 1
	for i := 0; i < offset && i < len(data); i++ {
		if data[i] == '\n' {
			line++
			col = 1
		} else {
			col++
		}
	}
	return line, col
}

func (p *parser) errorf(format string, args ...interface{}) error {
	line, col := lineCol(p.data, p.pos)
	msg := fmt.Sprintf(format, args...)
	return &parseError{p.pos, fmt.Sprintf("%s at byte offset %d (line %d, col %d)", msg, p.pos, line, col)}
}

func parseJSON(data []byte) (*Value, error) {
	p := &parser{data: data}
	p.skipWS()
	v, err := p.parseValue()
	if err != nil {
		return nil, err
	}
	p.skipWS()
	if p.pos != len(p.data) {
		return nil, p.errorf("unexpected trailing data")
	}
	return v, nil
}

func (p *parser) skipWS() {
	for p.pos < len(p.data) {
		switch p.data[p.pos] {
		case ' ', '\t', '\n', '\r':
			p.pos++
		default:
			return
		}
	}
}

func (p *parser) peek() (byte, bool) {
	if p.pos >= len(p.data) {
		return 0, false
	}
	return p.data[p.pos], true
}

func (p *parser) parseValue() (*Value, error) {
	c, ok := p.peek()
	if !ok {
		return nil, p.errorf("unexpected end of input")
	}
	switch {
	case c == '"':
		s, err := p.parseString()
		if err != nil {
			return nil, err
		}
		return &Value{Kind: KString, Str: s}, nil
	case c == '{':
		return p.parseObject()
	case c == '[':
		return p.parseArray()
	case c == 't':
		return p.parseLiteral("true", &Value{Kind: KBool, Bool: true})
	case c == 'f':
		return p.parseLiteral("false", &Value{Kind: KBool, Bool: false})
	case c == 'n':
		return p.parseLiteral("null", &Value{Kind: KNull})
	case c == '-' || (c >= '0' && c <= '9'):
		return p.parseNumber()
	default:
		return nil, p.errorf("unexpected character %q", c)
	}
}

func (p *parser) parseLiteral(word string, v *Value) (*Value, error) {
	if p.pos+len(word) > len(p.data) || string(p.data[p.pos:p.pos+len(word)]) != word {
		return nil, p.errorf("invalid literal, expected %q", word)
	}
	p.pos += len(word)
	return v, nil
}

func (p *parser) parseNumber() (*Value, error) {
	start := p.pos
	if b, ok := p.peek(); ok && b == '-' {
		p.pos++
	}
	if p.pos >= len(p.data) || p.data[p.pos] < '0' || p.data[p.pos] > '9' {
		return nil, p.errorf("invalid number")
	}
	if p.data[p.pos] == '0' {
		p.pos++
	} else {
		for p.pos < len(p.data) && p.data[p.pos] >= '0' && p.data[p.pos] <= '9' {
			p.pos++
		}
	}
	if p.pos < len(p.data) && p.data[p.pos] == '.' {
		p.pos++
		if p.pos >= len(p.data) || p.data[p.pos] < '0' || p.data[p.pos] > '9' {
			return nil, p.errorf("invalid number")
		}
		for p.pos < len(p.data) && p.data[p.pos] >= '0' && p.data[p.pos] <= '9' {
			p.pos++
		}
	}
	if p.pos < len(p.data) && (p.data[p.pos] == 'e' || p.data[p.pos] == 'E') {
		p.pos++
		if p.pos < len(p.data) && (p.data[p.pos] == '+' || p.data[p.pos] == '-') {
			p.pos++
		}
		if p.pos >= len(p.data) || p.data[p.pos] < '0' || p.data[p.pos] > '9' {
			return nil, p.errorf("invalid number")
		}
		for p.pos < len(p.data) && p.data[p.pos] >= '0' && p.data[p.pos] <= '9' {
			p.pos++
		}
	}
	text := string(p.data[start:p.pos])
	f, err := strconv.ParseFloat(text, 64)
	if err != nil {
		return nil, &parseError{start, fmt.Sprintf("invalid number %q at byte offset %d", text, start)}
	}
	return &Value{Kind: KNumber, Num: f}, nil
}

func (p *parser) parseString() (string, error) {
	if b, ok := p.peek(); !ok || b != '"' {
		return "", p.errorf("expected string")
	}
	p.pos++ // consume opening quote
	var sb strings.Builder
	for {
		if p.pos >= len(p.data) {
			return "", p.errorf("unterminated string")
		}
		c := p.data[p.pos]
		if c == '"' {
			p.pos++
			return sb.String(), nil
		}
		if c == '\\' {
			p.pos++
			if p.pos >= len(p.data) {
				return "", p.errorf("unterminated escape")
			}
			esc := p.data[p.pos]
			switch esc {
			case '"':
				sb.WriteByte('"')
				p.pos++
			case '\\':
				sb.WriteByte('\\')
				p.pos++
			case '/':
				sb.WriteByte('/')
				p.pos++
			case 'b':
				sb.WriteByte('\b')
				p.pos++
			case 'f':
				sb.WriteByte('\f')
				p.pos++
			case 'n':
				sb.WriteByte('\n')
				p.pos++
			case 'r':
				sb.WriteByte('\r')
				p.pos++
			case 't':
				sb.WriteByte('\t')
				p.pos++
			case 'u':
				p.pos++
				r1, err := p.readHex4()
				if err != nil {
					return "", err
				}
				if utf16.IsSurrogate(rune(r1)) {
					// try to read a trailing surrogate
					if p.pos+1 < len(p.data) && p.data[p.pos] == '\\' && p.data[p.pos+1] == 'u' {
						save := p.pos
						p.pos += 2
						r2, err := p.readHex4()
						if err != nil {
							return "", err
						}
						dec := utf16.DecodeRune(rune(r1), rune(r2))
						if dec != utf8.RuneError {
							sb.WriteRune(dec)
							continue
						}
						p.pos = save
					}
					sb.WriteRune(utf8.RuneError)
				} else {
					sb.WriteRune(rune(r1))
				}
			default:
				return "", p.errorf("invalid escape \\%c", esc)
			}
			continue
		}
		if c < 0x20 {
			return "", p.errorf("invalid control character in string")
		}
		sb.WriteByte(c)
		p.pos++
	}
}

func (p *parser) readHex4() (int, error) {
	if p.pos+4 > len(p.data) {
		return 0, p.errorf("invalid \\u escape")
	}
	n, err := strconv.ParseUint(string(p.data[p.pos:p.pos+4]), 16, 32)
	if err != nil {
		return 0, p.errorf("invalid \\u escape")
	}
	p.pos += 4
	return int(n), nil
}

func (p *parser) parseArray() (*Value, error) {
	p.pos++ // consume '['
	v := &Value{Kind: KArray}
	p.skipWS()
	if b, ok := p.peek(); ok && b == ']' {
		p.pos++
		return v, nil
	}
	for {
		p.skipWS()
		elem, err := p.parseValue()
		if err != nil {
			return nil, err
		}
		v.Arr = append(v.Arr, elem)
		p.skipWS()
		b, ok := p.peek()
		if !ok {
			return nil, p.errorf("unterminated array")
		}
		if b == ',' {
			p.pos++
			continue
		}
		if b == ']' {
			p.pos++
			return v, nil
		}
		return nil, p.errorf("expected ',' or ']'")
	}
}

func (p *parser) parseObject() (*Value, error) {
	p.pos++ // consume '{'
	v := &Value{Kind: KObject}
	p.skipWS()
	if b, ok := p.peek(); ok && b == '}' {
		p.pos++
		return v, nil
	}
	for {
		p.skipWS()
		if b, ok := p.peek(); !ok || b != '"' {
			return nil, p.errorf("expected string key")
		}
		key, err := p.parseString()
		if err != nil {
			return nil, err
		}
		p.skipWS()
		if b, ok := p.peek(); !ok || b != ':' {
			return nil, p.errorf("expected ':'")
		}
		p.pos++
		p.skipWS()
		val, err := p.parseValue()
		if err != nil {
			return nil, err
		}
		v.Obj = append(v.Obj, ObjEntry{Key: key, Val: val})
		p.skipWS()
		b, ok := p.peek()
		if !ok {
			return nil, p.errorf("unterminated object")
		}
		if b == ',' {
			p.pos++
			continue
		}
		if b == '}' {
			p.pos++
			return v, nil
		}
		return nil, p.errorf("expected ',' or '}'")
	}
}

// ---- serialization ----

func formatNumber(f float64) string {
	b, err := json.Marshal(f)
	if err != nil {
		return strconv.FormatFloat(f, 'g', -1, 64)
	}
	return string(b)
}

func encodeJSONString(s string) string {
	var sb strings.Builder
	sb.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"':
			sb.WriteString(`\"`)
		case '\\':
			sb.WriteString(`\\`)
		case '\n':
			sb.WriteString(`\n`)
		case '\r':
			sb.WriteString(`\r`)
		case '\t':
			sb.WriteString(`\t`)
		case '\b':
			sb.WriteString(`\b`)
		case '\f':
			sb.WriteString(`\f`)
		default:
			if r < 0x20 {
				fmt.Fprintf(&sb, `\u%04x`, r)
			} else {
				sb.WriteRune(r)
			}
		}
	}
	sb.WriteByte('"')
	return sb.String()
}

func serializeCompact(v *Value, sb *strings.Builder) {
	switch v.Kind {
	case KString:
		sb.WriteString(encodeJSONString(v.Str))
	case KNumber:
		sb.WriteString(formatNumber(v.Num))
	case KBool:
		if v.Bool {
			sb.WriteString("true")
		} else {
			sb.WriteString("false")
		}
	case KNull:
		sb.WriteString("null")
	case KArray:
		sb.WriteByte('[')
		for i, e := range v.Arr {
			if i > 0 {
				sb.WriteByte(',')
			}
			serializeCompact(e, sb)
		}
		sb.WriteByte(']')
	case KObject:
		sb.WriteByte('{')
		for i, e := range v.Obj {
			if i > 0 {
				sb.WriteByte(',')
			}
			sb.WriteString(encodeJSONString(e.Key))
			sb.WriteByte(':')
			serializeCompact(e.Val, sb)
		}
		sb.WriteByte('}')
	}
}

// ---- shape ----

func shapeOf(v *Value, depth int) string {
	switch v.Kind {
	case KString:
		return "string"
	case KNumber:
		return "number"
	case KBool:
		return "boolean"
	case KNull:
		return "null"
	case KArray:
		l := len(v.Arr)
		if l == 0 {
			return "array[0]"
		}
		if depth <= 0 {
			return fmt.Sprintf("array[%d]", l)
		}
		return fmt.Sprintf("array[%d] of %s", l, shapeOf(v.Arr[0], depth-1))
	case KObject:
		n := len(v.Obj)
		if depth <= 0 {
			return fmt.Sprintf("object{%d keys}", n)
		}
		if n == 0 {
			return "object{}"
		}
		parts := make([]string, n)
		for i, e := range v.Obj {
			parts[i] = e.Key + ": " + shapeOf(e.Val, depth-1)
		}
		return "object{" + strings.Join(parts, ", ") + "}"
	}
	return ""
}
