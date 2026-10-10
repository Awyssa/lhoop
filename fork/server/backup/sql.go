// Package backup is the server that keeps a copy of the LHOOP app's database, and its commands.
package backup

import (
	"errors"
	"fmt"
	"net/url"
	"path/filepath"
	"strings"
	"time"

	"zombiezen.com/go/sqlite"
)

// Format is the version of what the app and the server say to each other.
const Format = 1

const busyTimeout = 30 * time.Second

// quote writes an identifier as SQL. Names come from the schema, never from a request body.
func quote(name string) string {
	return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
}

// fileURI names a database file for ATTACH, with the mode it may be opened in: "ro" or "rw".
func fileURI(path, mode string) string {
	abs, err := filepath.Abs(path)
	if err != nil {
		abs = path
	}
	return (&url.URL{Scheme: "file", Path: abs}).String() + "?mode=" + mode
}

// connect opens a database in mode "ro", "rw" or "rwc" (create it if it is not there).
//
// The connection treats every file it is given as data: no function or view of a file's own runs, and
// SQLite's defensive mode is on. It never uses write-ahead logging, so a database is always one whole
// file, which is what lets an export read the copy's file directly while holding a read lock.
func connect(path, mode string) (*sqlite.Conn, error) {
	flags := sqlite.OpenURI
	switch mode {
	case "ro":
		flags |= sqlite.OpenReadOnly
	case "rw":
		flags |= sqlite.OpenReadWrite
	case "rwc":
		flags |= sqlite.OpenReadWrite | sqlite.OpenCreate
	default:
		return nil, fmt.Errorf("unknown mode %q", mode)
	}
	conn, err := sqlite.OpenConn(path, flags)
	if err != nil {
		return nil, err
	}
	conn.SetBusyTimeout(busyTimeout)
	pragmas := []string{"PRAGMA trusted_schema = OFF", "PRAGMA foreign_keys = OFF", "PRAGMA temp_store = MEMORY"}
	if mode != "ro" {
		pragmas = append(pragmas, "PRAGMA synchronous = FULL")
	}
	for _, pragma := range pragmas {
		if err := exec(conn, pragma); err != nil {
			conn.Close()
			return nil, err
		}
	}
	if err := conn.SetDefensive(true); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

// run executes one statement and calls each for every row it returns. A nil argument binds NULL.
func run(conn *sqlite.Conn, query string, args []any, each func(*sqlite.Stmt) error) error {
	stmt, trailing, err := conn.PrepareTransient(query)
	if err != nil {
		return err
	}
	defer stmt.Finalize()
	if trailing != 0 {
		return errors.New("more than one statement")
	}
	for i, arg := range args {
		param := i + 1
		switch v := arg.(type) {
		case nil:
			stmt.BindNull(param)
		case int:
			stmt.BindInt64(param, int64(v))
		case int64:
			stmt.BindInt64(param, v)
		case float64:
			stmt.BindFloat(param, v)
		case string:
			stmt.BindText(param, v)
		case []byte:
			stmt.BindBytes(param, v)
		default:
			return fmt.Errorf("an argument SQLite has no kind for: %T", arg)
		}
	}
	for {
		more, err := stmt.Step()
		if err != nil {
			return err
		}
		if !more {
			return nil
		}
		if each != nil {
			if err := each(stmt); err != nil {
				return err
			}
		}
	}
}

func exec(conn *sqlite.Conn, query string, args ...any) error {
	return run(conn, query, args, nil)
}

// oneInt returns the first column of the first row. ok is false when there is no row or it is NULL.
func oneInt(conn *sqlite.Conn, query string, args ...any) (value int64, ok bool, err error) {
	err = run(conn, query, args, func(stmt *sqlite.Stmt) error {
		if !ok && stmt.ColumnType(0) != sqlite.TypeNull {
			value, ok = stmt.ColumnInt64(0), true
		}
		return nil
	})
	return value, ok, err
}

func oneText(conn *sqlite.Conn, query string, args ...any) (value string, ok bool, err error) {
	err = run(conn, query, args, func(stmt *sqlite.Stmt) error {
		if !ok && stmt.ColumnType(0) != sqlite.TypeNull {
			value, ok = stmt.ColumnText(0), true
		}
		return nil
	})
	return value, ok, err
}
