package main

import "testing"

func TestMediaHiddenPrefixesAndVPath(t *testing.T) {
	cfg := &Config{Libraries: []Library{
		{ID: "lib1", MediaHide: []string{"photos/private", "/videos\\x/"}},
		{ID: "lib2", MediaHide: []string{"music"}},
	}}
	prefixes := mediaHiddenPrefixes(cfg)
	want := map[string]bool{
		"/lib1/photos/private": true,
		"/lib1/videos/x":       true,
		"/lib2/music":          true,
	}
	if len(prefixes) != len(want) {
		t.Fatalf("前缀数量 = %d, want %d: %#v", len(prefixes), len(want), prefixes)
	}
	for _, p := range prefixes {
		if !want[p] {
			t.Errorf("意外的前缀 %q", p)
		}
	}

	cases := []struct {
		vpath string
		want  bool
	}{
		{"/lib1/photos/private", true},
		{"/lib1/photos/private/a.jpg", true},
		{"/lib1/Photos/PRIVATE/sub/b.png", true}, // 大小写不敏感
		{"/lib1/photos/private2/a.jpg", false},   // 相似前缀不应命中
		{"/lib1/photos/a.jpg", false},
		{"/lib1/videos/x/y.mp4", true},
		{"/lib2/music/song.mp3", true},
		{"/lib2/music2/song.mp3", false},
		{"/lib3/anything.jpg", false},
	}
	for _, c := range cases {
		if got := vpathMediaHidden(prefixes, c.vpath); got != c.want {
			t.Errorf("vpathMediaHidden(%q) = %v, want %v", c.vpath, got, c.want)
		}
	}
}

func TestSetMediaHideDedupAndAncestor(t *testing.T) {
	lib := Library{ID: "lib1"}
	lib.setMediaHide("dir", true)
	lib.setMediaHide("dir/sub", true)          // 被祖先覆盖,冗余,不新增
	lib.setMediaHide("dir/sub/file.jpg", true) // 仍被祖先覆盖
	if len(lib.MediaHide) != 1 || lib.MediaHide[0] != "dir" {
		t.Fatalf("祖先覆盖后应为 [dir],得到 %#v", lib.MediaHide)
	}

	lib.setMediaHide("other", true)
	if len(lib.MediaHide) != 2 {
		t.Fatalf("应有两个互不覆盖的项,得到 %#v", lib.MediaHide)
	}

	// 取消隐藏 dir:移除自身,其它保留。
	lib.setMediaHide("dir", false)
	if len(lib.MediaHide) != 1 || lib.MediaHide[0] != "other" {
		t.Fatalf("取消隐藏后应为 [other],得到 %#v", lib.MediaHide)
	}
}

func TestSetMediaHideReplacesDescendants(t *testing.T) {
	lib := Library{ID: "lib1", MediaHide: []string{"dir/sub"}}
	lib.setMediaHide("dir", true) // 新父项覆盖后代,应清掉 dir/sub
	if len(lib.MediaHide) != 1 || lib.MediaHide[0] != "dir" {
		t.Fatalf("添加父项后应只剩 [dir],得到 %#v", lib.MediaHide)
	}
	lib.setMediaHide("dir", false) // 取消父项,自身与后代都应清理
	if len(lib.MediaHide) != 0 {
		t.Fatalf("取消隐藏后应为空,得到 %#v", lib.MediaHide)
	}
}

// TestMediaExcludesHiddenPrefix 验证 index.Media 在 SQL 层排除被隐藏前缀(含相似前缀不误伤)。
func TestMediaExcludesHiddenPrefix(t *testing.T) {
	ix, err := OpenIndex(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ix.Close()
	const ins = `INSERT INTO files (lib_id,path,parent,name,is_dir,size,mod_time,kind)
		VALUES (?,?,?,?,0,?,1,?)`
	rows := []struct{ lib, path, name, kind string }{
		{"lib1", "/lib1/pub/a.jpg", "a.jpg", "image"},
		{"lib1", "/lib1/priv/b.jpg", "b.jpg", "image"},
		{"lib1", "/lib1/priv/sub/c.jpg", "c.jpg", "image"},
		{"lib1", "/lib1/pubv/d.jpg", "d.jpg", "image"},
	}
	for _, r := range rows {
		if _, e := ix.db.Exec(ins, r.lib, r.path, DirVirtual(r.path), r.name, int64(10), r.kind); e != nil {
			t.Fatal(e)
		}
	}
	hits, err := ix.Media("image", "", "", 0, 100, []string{"/lib1/priv"})
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, h := range hits {
		names[h.Name] = true
	}
	if names["b.jpg"] || names["c.jpg"] {
		t.Errorf("被隐藏前缀(含子目录)的文件不应出现: %#v", hits)
	}
	if !names["a.jpg"] || !names["d.jpg"] {
		t.Errorf("未隐藏的文件应出现(相似前缀 pubv 不应误伤): %#v", hits)
	}
}
