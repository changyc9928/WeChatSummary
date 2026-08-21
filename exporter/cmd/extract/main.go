// Command extract decrypts a WeChat 4.x MSG.db with a verified key and writes
// the message JSON export consumed by the WeChatSummary backend upload step.
//
// Usage:
//
//	extract -db MSG.db -key <64-hex-key|96-hex-key|keyfile.json> [-out chat.json] [-zip chat.zip] [-limit N]
//
// The key must already be verified (bridge /api/key/validate or
// /api/key/autofind). Column mapping is heuristic (WeChat versions differ);
// run without -out to print a summary to stderr first.
package main

import (
	"archive/zip"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"wechatsummary/exporter/internal/extract"
	"wechatsummary/exporter/internal/sqlcipher"
	"wechatsummary/exporter/internal/sqlite"
	"wechatsummary/exporter/internal/util"
)

func main() {
	var (
		dbPath = flag.String("db", "", "path to the WeChat MSG.db (encrypted)")
		key    = flag.String("key", "", "64-hex raw key, 96-hex key+salt, or a key JSON cache file path")
		out    = flag.String("out", "", "write the JSON export here (default: stdout)")
		zipOut = flag.String("zip", "", "write a ZIP containing the JSON export here")
		limit  = flag.Int("limit", 0, "max messages to read (0 = all, ascending rowid)")
	)
	flag.Parse()

	if *dbPath == "" || *key == "" {
		fmt.Fprintln(os.Stderr, "extract: -db and -key are required")
		flag.Usage()
		os.Exit(2)
	}

	secret, _, _, kerr := util.ParseKeyInput(*key)
	if kerr != nil {
		fatal("cannot parse key: %v", kerr)
	}

	f, oerr := sqlcipher.Open(*dbPath, secret, sqlcipher.ModeRaw, false)
	if oerr != nil {
		fatal("cannot open database (wrong key?): %v", oerr)
	}
	defer f.Close()

	db, derr := sqlite.Open(f)
	if derr != nil {
		fatal("cannot parse decrypted database: %v", derr)
	}

	tables, terr := extract.FindMessageTables(db)
	if terr != nil {
		fatal("extract: %v", terr)
	}

	name2id := extract.LoadName2Id(db)
	var msgs []extract.Message
	cols := []string{}
	tableNames := []string{}
	for _, mt := range tables {
		part, merr := extract.ExtractMessages(mt.Table, mt.Columns, *limit, name2id)
		if merr != nil {
			fatal("extract: table %s: %v", mt.Table.Name(), merr)
		}
		msgs = append(msgs, part...)
		tableNames = append(tableNames, mt.Table.Name())
		if len(cols) == 0 {
			cols = mt.Columns
		}
	}

	nickname := inferNickname(*dbPath)
	doc := extract.BuildExport(msgs, nickname)

	if *zipOut != "" {
		if err := writeZip(*zipOut, "messages.json", doc); err != nil {
			fatal("zip: %v", err)
		}
		fmt.Fprintf(os.Stderr, "wrote %s (%d messages)\n", *zipOut, len(msgs))
	}

	if *out != "" {
		b, err := extract.Marshal(doc)
		if err != nil {
			fatal("marshal: %v", err)
		}
		if err := os.WriteFile(*out, b, 0o644); err != nil {
			fatal("write %s: %v", *out, err)
		}
		fmt.Fprintf(os.Stderr, "wrote %s (%d messages)\n", *out, len(msgs))
	} else if *zipOut == "" {
		b, err := extract.Marshal(doc)
		if err != nil {
			fatal("marshal: %v", err)
		}
		os.Stdout.Write(b)
		fmt.Fprintln(os.Stderr, "")
	}

	fmt.Fprintf(os.Stderr, "tables=%s mode=%s messages=%d\n",
		strings.Join(tableNames, ","), f.Mode().String(), len(msgs))
}

func writeZip(path, name string, doc extract.Export) error {
	zf, err := os.Create(path)
	if err != nil {
		return err
	}
	defer zf.Close()
	zw := zip.NewWriter(zf)
	w, err := zw.Create(name)
	if err != nil {
		return err
	}
	b, err := extract.Marshal(doc)
	if err != nil {
		return err
	}
	if _, err := w.Write(b); err != nil {
		return err
	}
	return zw.Close()
}

// inferNickname uses the enclosing directory name (usually the wxid) as the
// session nickname; falls back to the file name.
func inferNickname(dbPath string) string {
	abs, err := filepath.Abs(dbPath)
	if err != nil {
		abs = dbPath
	}
	if dir := filepath.Base(filepath.Dir(abs)); dir != "." && dir != "" && !strings.EqualFold(dir, "msg") {
		return dir
	}
	return filepath.Base(abs)
}

func fatal(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "extract: "+format+"\n", args...)
	os.Exit(1)
}
