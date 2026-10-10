package backup

// The server's own records: what arrived, what was merged, what was read out. No health data is kept here.

import (
	"zombiezen.com/go/sqlite"
	"zombiezen.com/go/sqlite/sqlitex"
)

const ledgerSchema = `
CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS upload (
  id          INTEGER PRIMARY KEY,
  received_at INTEGER NOT NULL,
  kind        TEXT NOT NULL CHECK (kind IN ('schema', 'delta')),
  sha256      TEXT NOT NULL UNIQUE,
  bytes       INTEGER NOT NULL,
  row_count   INTEGER NOT NULL,
  app_build   TEXT,
  status      TEXT NOT NULL CHECK (status IN ('merged', 'refused')),
  error       TEXT,
  log_file    TEXT
);
CREATE TABLE IF NOT EXISTS bucket (
  table_name  TEXT NOT NULL,
  device_id   TEXT NOT NULL,
  hour        INTEGER NOT NULL,
  phone_print TEXT NOT NULL,
  row_count   INTEGER NOT NULL,
  upload_id   INTEGER NOT NULL REFERENCES upload(id),
  PRIMARY KEY (table_name, device_id, hour)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS export (
  id    INTEGER PRIMARY KEY,
  at    INTEGER NOT NULL,
  days  INTEGER,
  bytes INTEGER NOT NULL
);
`

// Ledger is one connection to ledger.sqlite. Open one per piece of work and close it; a write waits its turn.
type Ledger struct {
	conn *sqlite.Conn
}

func openLedger(path string) (*Ledger, error) {
	conn, err := connect(path, "rwc")
	if err != nil {
		return nil, err
	}
	if err := sqlitex.ExecScript(conn, ledgerSchema); err != nil {
		conn.Close()
		return nil, err
	}
	return &Ledger{conn}, nil
}

func (l *Ledger) Close() { l.conn.Close() }

// get returns a fact about the copy, and whether it is known.
func (l *Ledger) get(key string) (string, bool) {
	value, ok, _ := oneText(l.conn, "SELECT value FROM meta WHERE key = ?", key)
	return value, ok
}

func (l *Ledger) put(values map[string]string) error {
	for _, key := range sortedKeys(values) {
		if err := exec(l.conn, "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", key, values[key]); err != nil {
			return err
		}
	}
	return nil
}

// uploadRow is what the ledger remembers of one upload.
type uploadRow struct {
	ID      int64
	Kind    string
	SHA256  string
	Status  string
	Rows    int64
	LogFile string
}

// upload finds the upload with this checksum.
func (l *Ledger) upload(sha256 string) (*uploadRow, error) {
	var found *uploadRow
	err := run(l.conn, "SELECT id, kind, sha256, status, row_count, coalesce(log_file, '') FROM upload WHERE sha256 = ?", []any{sha256},
		func(row *sqlite.Stmt) error {
			found = &uploadRow{row.ColumnInt64(0), row.ColumnText(1), row.ColumnText(2), row.ColumnText(3), row.ColumnInt64(4), row.ColumnText(5)}
			return nil
		})
	return found, err
}

type uploadRecord struct {
	ReceivedAt int64
	Kind       string
	SHA256     string
	Size       int64
	Rows       int64
	AppBuild   string
	Status     string
	Error      string
	LogFile    string
	Buckets    []mergedBucket
	Meta       map[string]string
}

func orNull(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// record writes one upload and what it changed, in one step. A refused upload sent again replaces its earlier row.
func (l *Ledger) record(r uploadRecord) (id int64, err error) {
	if err := exec(l.conn, "BEGIN IMMEDIATE"); err != nil {
		return 0, err
	}
	defer func() {
		if err != nil {
			exec(l.conn, "ROLLBACK")
		}
	}()
	if err = exec(l.conn, "DELETE FROM upload WHERE sha256 = ? AND status = 'refused'", r.SHA256); err != nil {
		return 0, err
	}
	err = exec(l.conn, "INSERT INTO upload (received_at, kind, sha256, bytes, row_count, app_build, status, error, log_file) "+
		"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
		r.ReceivedAt, r.Kind, r.SHA256, r.Size, r.Rows, orNull(r.AppBuild), r.Status, orNull(r.Error), orNull(r.LogFile))
	if err != nil {
		return 0, err
	}
	id = l.conn.LastInsertRowID()
	for _, b := range r.Buckets {
		err = exec(l.conn, "INSERT OR REPLACE INTO bucket (table_name, device_id, hour, phone_print, row_count, upload_id) VALUES (?, ?, ?, ?, ?, ?)",
			b.Table, b.Device, b.Hour, b.Print, b.Rows, id)
		if err != nil {
			return 0, err
		}
	}
	if err = l.put(r.Meta); err != nil {
		return 0, err
	}
	if err = exec(l.conn, "COMMIT"); err != nil {
		return 0, err
	}
	return id, nil
}

// prints returns the phone's checksum of every hour of a table the server has merged.
func (l *Ledger) prints(table string) (map[Bucket]string, error) {
	out := map[Bucket]string{}
	err := run(l.conn, "SELECT device_id, hour, phone_print FROM bucket WHERE table_name = ?", []any{table}, func(row *sqlite.Stmt) error {
		out[Bucket{row.ColumnText(0), row.ColumnInt64(1)}] = row.ColumnText(2)
		return nil
	})
	return out, err
}

func (l *Ledger) buckets() ([]mergedBucket, error) {
	var out []mergedBucket
	err := run(l.conn, "SELECT table_name, device_id, hour, phone_print, row_count FROM bucket ORDER BY 1, 2, 3", nil, func(row *sqlite.Stmt) error {
		out = append(out, mergedBucket{row.ColumnText(0), row.ColumnText(1), row.ColumnInt64(2), row.ColumnText(3), row.ColumnInt64(4)})
		return nil
	})
	return out, err
}

// mergedUploads returns every merged upload, oldest first.
func (l *Ledger) mergedUploads() ([]uploadRow, error) {
	var out []uploadRow
	err := run(l.conn, "SELECT id, kind, sha256, status, row_count, coalesce(log_file, '') FROM upload WHERE status = 'merged' ORDER BY id", nil,
		func(row *sqlite.Stmt) error {
			out = append(out, uploadRow{row.ColumnInt64(0), row.ColumnText(1), row.ColumnText(2), row.ColumnText(3), row.ColumnInt64(4), row.ColumnText(5)})
			return nil
		})
	return out, err
}

func (l *Ledger) recordExport(at int64, days *int, size int64) error {
	var d any
	if days != nil {
		d = *days
	}
	return exec(l.conn, "INSERT INTO export (at, days, bytes) VALUES (?, ?, ?)", at, d, size)
}

type ledgerSummary struct {
	Merged, Refused     int64
	LastAt              int64
	HasLast             bool
	Hours               int64
	FirstHour, LastHour int64
	Exports             int64
}

func (l *Ledger) summary() (ledgerSummary, error) {
	var s ledgerSummary
	var err error
	if s.Merged, _, err = oneInt(l.conn, "SELECT count(*) FROM upload WHERE status = 'merged'"); err != nil {
		return s, err
	}
	s.Refused, _, _ = oneInt(l.conn, "SELECT count(*) FROM upload WHERE status = 'refused'")
	s.LastAt, s.HasLast, _ = oneInt(l.conn, "SELECT received_at FROM upload WHERE status = 'merged' ORDER BY id DESC LIMIT 1")
	s.Exports, _, _ = oneInt(l.conn, "SELECT count(*) FROM export")
	err = run(l.conn, "SELECT count(*), coalesce(min(hour), 0), coalesce(max(hour), 0) FROM bucket", nil, func(row *sqlite.Stmt) error {
		s.Hours, s.FirstHour, s.LastHour = row.ColumnInt64(0), row.ColumnInt64(1), row.ColumnInt64(2)
		return nil
	})
	return s, err
}
