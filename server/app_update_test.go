package main

import "testing"

func TestCompareVersion(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"1.8.0", "1.8.0", 0},
		{"1.8.0", "1.7.0", 1},
		{"1.7.0", "1.8.0", -1},
		{"1.10.0", "1.9.9", 1},
		{"2.0", "1.99.99", 1},
		{"1.8", "1.8.0", 0},
		{"1.8.1", "1.8", 1},
	}
	for _, c := range cases {
		if got := compareVersion(c.a, c.b); got != c.want {
			t.Errorf("compareVersion(%q,%q)=%d want %d", c.a, c.b, got, c.want)
		}
	}
}

func TestVersionFromAPKName(t *testing.T) {
	cases := []struct {
		name string
		want string
	}{
		{"linNas-1.8.0-debug.apk", "1.8.0"},
		{"linNas-1.7.0-debug.apk", "1.7.0"},
		{"linNas-1.10.2-debug.apk", "1.10.2"},
		{"weird.apk", ""},
	}
	for _, c := range cases {
		if got := versionFromAPKName(c.name); got != c.want {
			t.Errorf("versionFromAPKName(%q)=%q want %q", c.name, got, c.want)
		}
	}
}
