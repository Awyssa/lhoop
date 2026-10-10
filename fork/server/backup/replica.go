package backup

// The copy of the phone's database: making it, merging uploads into it, and writing it out as a backup.
//
// The copy has the phone's own tables and nothing else, so the app's Import backup restores it. Its
// tables are of two kinds (isSample). Sample tables are merged hour by hour and rows are only ever
// added or overwritten, never deleted: the phone drops old rows of its own accord and the copy must
// not follow it. Small tables are replaced whole.

import (
	"archive/zip"
	"compress/flate"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"regexp"
	"sort"
	"strings"

	"zombiezen.com/go/sqlite"
)

// The three tables a delta file carries beside the rows.
const (
	deltaMeta   = "_lhoop_delta"  // key, value: format, user_version, identity_hash, app_build, app_version, settings_json
	deltaBucket = "_lhoop_bucket" // table_name, device_id, hour, print: the hours carried in full
	deltaSmall  = "_lhoop_small"  // table_name: the small tables carried whole
)

// The core keeps only the newest rows of these two tables on the phone
// (WhoopRepository.V18_AUX_RETENTION_ROWS and PPG_WAVEFORM_RETENTION_ROWS). The copy keeps all of them;
// a backup trimmed for the phone gets the phone's caps back.
var caps = map[string]int64{"v18AuxSample": 604_800, "ppgWaveformSample": 604_800}

// The names the app's own export uses (data/DataBackup.kt, BackupSettings.kt, BackupProvenance.kt).
const (
	dbEntry       = "lhoop-backup.sqlite"
	settingsEntry = "settings.json"
	manifestEntry = "manifest.json"
)

const maxStatements = 500

var (
	ddlPattern   = regexp.MustCompile(`(?i)^\s*CREATE\s+(TABLE|(UNIQUE\s+)?INDEX)\b`)
	plainTable   = regexp.MustCompile(`(?i)^\s*CREATE\s+TABLE\b`)
	schemaTables = map[string]bool{"sqlite_master": true, "sqlite_schema": true}
)

// Refused means what was sent cannot be accepted. The message goes back to the sender, so it names no row.
type Refused struct {
	Message string
	Status  int
}

func (r *Refused) Error() string { return r.Message }

func refuse(status int, format string, args ...any) *Refused {
	return &Refused{Message: fmt.Sprintf(format, args...), Status: status}
}

// Shape is the tables of a database and their columns.
type Shape struct {
	Tables map[string][]Column
	names  []string // in name order
}

func (s Shape) sample() []string { return s.pick(true) }
func (s Shape) small() []string  { return s.pick(false) }

func (s Shape) pick(sample bool) []string {
	var out []string
	for _, name := range s.names {
		if isSample(s.Tables[name]) == sample {
			out = append(out, name)
		}
	}
	return out
}

func shapeOf(conn *sqlite.Conn, schema string) (Shape, error) {
	out := Shape{Tables: map[string][]Column{}}
	err := run(conn, fmt.Sprintf("SELECT name FROM %s.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%%' ORDER BY name", schema), nil,
		func(row *sqlite.Stmt) error {
			out.names = append(out.names, row.ColumnText(0))
			return nil
		})
	if err != nil {
		return out, err
	}
	for _, name := range out.names {
		if out.Tables[name], err = columns(conn, schema, name); err != nil {
			return out, err
		}
	}
	return out, nil
}

// ddlOnly is the rule while the copy is being created: a statement may create a table or an index and
// do nothing else.
//
// Reading a column is allowed because building a key's index reads the key. It gives nothing away: a
// SELECT needs its own permission, which is never given, so CREATE TABLE ... AS SELECT is refused.
func ddlOnly(action sqlite.Action) sqlite.AuthResult {
	switch action.Type() {
	case sqlite.OpCreateTable, sqlite.OpCreateIndex, sqlite.OpReindex, sqlite.OpRead:
		return sqlite.AuthResultOK
	case sqlite.OpInsert, sqlite.OpUpdate:
		if schemaTables[action.Table()] {
			return sqlite.AuthResultOK
		}
	}
	return sqlite.AuthResultDeny
}

// createReplica makes the empty copy from the phone's own CREATE TABLE and CREATE INDEX statements.
// It returns the number of tables.
func createReplica(path string, userVersion int64, statements []any, pageSize, autoVacuum int64) (int, error) {
	if len(statements) == 0 || len(statements) > maxStatements {
		return 0, refuse(422, "the schema must be a list of statements")
	}
	if pageSize < 512 || pageSize > 65536 || pageSize&(pageSize-1) != 0 || autoVacuum < 0 || autoVacuum > 2 {
		return 0, refuse(422, "unknown page size or vacuum mode")
	}
	pending := path + ".new"
	os.Remove(pending)
	conn, err := sqlite.OpenConn(pending, sqlite.OpenReadWrite|sqlite.OpenCreate)
	if err != nil {
		return 0, err
	}
	made, closed := false, false
	defer func() {
		if !closed {
			conn.Close()
		}
		if !made {
			os.Remove(pending)
		}
	}()
	if err := exec(conn, fmt.Sprintf("PRAGMA page_size = %d", pageSize)); err != nil {
		return 0, err
	}
	if err := exec(conn, fmt.Sprintf("PRAGMA auto_vacuum = %d", autoVacuum)); err != nil {
		return 0, err
	}
	if err := conn.SetAuthorizer(sqlite.AuthorizeFunc(ddlOnly)); err != nil {
		return 0, err
	}
	for _, raw := range statements {
		statement, isText := raw.(string)
		if !isText || !ddlPattern.MatchString(statement) {
			return 0, refuse(422, "only CREATE TABLE and CREATE INDEX statements are accepted")
		}
		// run refuses a second statement after the first, so one string cannot smuggle another in.
		if err := exec(conn, strings.TrimSpace(statement)); err != nil {
			return 0, refuse(422, "a schema statement was not accepted")
		}
	}
	if err := conn.SetAuthorizer(nil); err != nil {
		return 0, err
	}
	if err := exec(conn, fmt.Sprintf("PRAGMA user_version = %d", userVersion)); err != nil {
		return 0, err
	}
	found, err := shapeOf(conn, "main")
	if err != nil {
		return 0, err
	}
	if len(found.sample()) == 0 {
		return 0, refuse(422, "the schema has no sample table")
	}
	closed = true
	if err := conn.Close(); err != nil {
		return 0, err
	}
	if err := os.Rename(pending, path); err != nil {
		return 0, err
	}
	made = true
	return len(found.Tables), nil
}

// mergedBucket is one hour an upload carried: the phone's checksum of it and the rows the copy now holds.
type mergedBucket struct {
	Table, Device string
	Hour          int64
	Print         string
	Rows          int64
}

// Merged is what one delta file did to the copy.
type Merged struct {
	Rows    map[string]int64 // rows written, by table
	Buckets []mergedBucket
	Small   []string
	Meta    map[string]string
}

// merge checks the delta file and merges it into the copy on conn, all of it or none of it.
func merge(conn *sqlite.Conn, deltaPath string, userVersion int64, identityHash string) (*Merged, error) {
	unreadable := refuse(422, "the upload is not a readable delta file")
	if err := exec(conn, "ATTACH DATABASE ? AS delta", fileURI(deltaPath, "ro")); err != nil {
		return nil, unreadable
	}
	defer exec(conn, "DETACH DATABASE delta")
	plan, err := checked(conn, userVersion, identityHash)
	if err != nil {
		var refused *Refused
		if errors.As(err, &refused) {
			return nil, refused
		}
		return nil, unreadable // SQLite could not read what the file claims to hold
	}
	return write(conn, plan)
}

type mergePlan struct {
	known  Shape
	sample map[string]map[Bucket]string
	small  []string
	meta   map[string]string
}

func checked(conn *sqlite.Conn, userVersion int64, identityHash string) (*mergePlan, error) {
	if verdict, _, err := oneText(conn, "PRAGMA delta.integrity_check"); err != nil {
		return nil, err
	} else if verdict != "ok" {
		return nil, refuse(422, "the upload is damaged")
	}
	inFile := map[string]bool{}
	var data []string
	err := run(conn, "SELECT type, name, sql FROM delta.sqlite_master", nil, func(row *sqlite.Stmt) error {
		kind, name, sql := row.ColumnText(0), row.ColumnText(1), row.ColumnText(2)
		switch {
		case kind == "table" && plainTable.MatchString(sql): // not a virtual table: those run code of their own
			inFile[name] = true
			if name != deltaMeta && name != deltaBucket && name != deltaSmall {
				data = append(data, name)
			}
		case kind == "index" && row.ColumnType(2) == sqlite.TypeNull: // an index SQLite made itself for a primary key
		default:
			return refuse(422, "the upload holds something other than tables")
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if !inFile[deltaMeta] || !inFile[deltaBucket] || !inFile[deltaSmall] {
		return nil, refuse(422, "the upload is not a delta file")
	}

	meta := map[string]string{}
	if err := run(conn, "SELECT key, value FROM delta."+deltaMeta, nil, func(row *sqlite.Stmt) error {
		meta[row.ColumnText(0)] = row.ColumnText(1)
		return nil
	}); err != nil {
		return nil, err
	}
	if meta["format"] != fmt.Sprint(Format) {
		return nil, refuse(422, "unknown delta format")
	}
	if meta["user_version"] != fmt.Sprint(userVersion) || meta["identity_hash"] != identityHash {
		return nil, refuse(409, "the upload was written for another schema than the copy's")
	}

	known, err := shapeOf(conn, "main")
	if err != nil {
		return nil, err
	}
	for _, table := range data {
		want, ok := known.Tables[table]
		if !ok {
			return nil, refuse(422, "the upload holds a table the copy does not have")
		}
		got, err := columns(conn, "delta", table)
		if err != nil {
			return nil, err
		}
		if !sameNames(got, want) {
			return nil, refuse(422, "the columns of %s are not the copy's", table)
		}
	}

	listed := map[string]map[Bucket]string{}
	if err := run(conn, "SELECT table_name, device_id, hour, print FROM delta."+deltaBucket, nil, func(row *sqlite.Stmt) error {
		if row.ColumnType(0) != sqlite.TypeText || row.ColumnType(1) != sqlite.TypeText ||
			row.ColumnType(2) != sqlite.TypeInteger || row.ColumnType(3) != sqlite.TypeText {
			return refuse(422, "the list of hours is malformed")
		}
		table := row.ColumnText(0)
		if listed[table] == nil {
			listed[table] = map[Bucket]string{}
		}
		listed[table][Bucket{row.ColumnText(1), row.ColumnInt64(2)}] = row.ColumnText(3)
		return nil
	}); err != nil {
		return nil, err
	}
	var small []string
	if err := run(conn, "SELECT table_name FROM delta."+deltaSmall, nil, func(row *sqlite.Stmt) error {
		small = append(small, row.ColumnText(0))
		return nil
	}); err != nil {
		return nil, err
	}

	wanted := map[string]bool{}
	for table := range listed {
		if cols, ok := known.Tables[table]; !ok || !isSample(cols) {
			return nil, refuse(422, "the upload lists a table of the wrong kind")
		}
		wanted[table] = true
	}
	for _, table := range small {
		if cols, ok := known.Tables[table]; !ok || isSample(cols) || wanted[table] {
			return nil, refuse(422, "the upload lists a table of the wrong kind")
		}
		wanted[table] = true
	}
	if len(wanted) != len(data) {
		return nil, refuse(422, "the tables in the upload are not the ones it lists")
	}
	for _, table := range data {
		if !wanted[table] {
			return nil, refuse(422, "the tables in the upload are not the ones it lists")
		}
	}
	// The server works every hour's checksum out again from the rows it was sent. A damaged file, a row
	// outside the hours listed, or a difference between the app's rule and this one all stop here.
	for table, hours := range listed {
		have, err := hourPrints(conn, "delta", table, known.Tables[table], nil)
		if err != nil {
			return nil, err
		}
		if len(have) != len(hours) {
			return nil, refuse(422, "the rows of %s do not match the checksums sent with them", table)
		}
		for bucket, print := range hours {
			if have[bucket] != print {
				return nil, refuse(422, "the rows of %s do not match the checksums sent with them", table)
			}
		}
	}
	sort.Strings(small)
	return &mergePlan{known: known, sample: listed, small: small, meta: meta}, nil
}

func sameNames(a, b []Column) bool {
	if len(a) != len(b) {
		return false
	}
	names := map[string]bool{}
	for _, c := range a {
		names[c.Name] = true
	}
	for _, c := range b {
		if !names[c.Name] {
			return false
		}
	}
	return len(names) == len(b)
}

func columnList(cols []Column) string {
	names := make([]string, len(cols))
	for i, c := range cols {
		names[i] = quote(c.Name)
	}
	return strings.Join(names, ", ")
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func write(conn *sqlite.Conn, plan *mergePlan) (*Merged, error) {
	out := &Merged{Rows: map[string]int64{}, Small: plan.small, Meta: plan.meta}
	if err := exec(conn, "BEGIN IMMEDIATE"); err != nil {
		return nil, err
	}
	err := func() error {
		for _, table := range sortedKeys(plan.sample) {
			names := columnList(plan.known.Tables[table])
			if err := exec(conn, fmt.Sprintf("INSERT OR REPLACE INTO main.%s (%s) SELECT %s FROM delta.%s", quote(table), names, names, quote(table))); err != nil {
				return err
			}
			out.Rows[table] = int64(conn.Changes())
		}
		for _, table := range plan.small {
			names := columnList(plan.known.Tables[table])
			if err := exec(conn, "DELETE FROM main."+quote(table)); err != nil {
				return err
			}
			if err := exec(conn, fmt.Sprintf("INSERT INTO main.%s (%s) SELECT %s FROM delta.%s", quote(table), names, names, quote(table))); err != nil {
				return err
			}
			out.Rows[table] = int64(conn.Changes())
		}
		return exec(conn, "COMMIT")
	}()
	if err != nil {
		exec(conn, "ROLLBACK")
		if sqlite.ErrCode(err).ToPrimary() == sqlite.ResultConstraint {
			return nil, refuse(422, "a row in the upload breaks a rule of its table")
		}
		return nil, err
	}

	for _, table := range sortedKeys(plan.sample) {
		hours := plan.sample[table]
		byDevice := map[string][]int64{}
		for bucket := range hours {
			byDevice[bucket.Device] = append(byDevice[bucket.Device], bucket.Hour)
		}
		for _, device := range sortedKeys(byDevice) {
			wanted := byDevice[device]
			sort.Slice(wanted, func(i, j int) bool { return wanted[i] < wanted[j] })
			held := map[int64]int64{}
			query := fmt.Sprintf("SELECT %s / %d, count(*) FROM main.%s WHERE %s = ? AND %s > ? AND %s < ? GROUP BY 1",
				quote(tsCol), hourSec, quote(table), quote(deviceCol), quote(tsCol), quote(tsCol))
			err := run(conn, query, []any{device, (wanted[0] - 1) * hourSec, (wanted[len(wanted)-1] + 1) * hourSec}, func(row *sqlite.Stmt) error {
				held[row.ColumnInt64(0)] = row.ColumnInt64(1)
				return nil
			})
			if err != nil {
				return nil, err
			}
			for _, hour := range wanted {
				out.Buckets = append(out.Buckets, mergedBucket{table, device, hour, hours[Bucket{device, hour}], held[hour]})
			}
		}
	}
	return out, nil
}

// newestTS is the newest second of heart rate, or of any sample table when there is no heart-rate table.
func newestTS(conn *sqlite.Conn, known Shape, schema string) (int64, bool, error) {
	tables := known.sample()
	if _, ok := known.Tables["hrSample"]; ok {
		tables = []string{"hrSample"}
	}
	var newest int64
	found := false
	for _, table := range tables {
		ts, ok, err := oneInt(conn, fmt.Sprintf("SELECT max(%s) FROM %s.%s", quote(tsCol), schema, quote(table)))
		if err != nil {
			return 0, false, err
		}
		if ok && (!found || ts > newest) {
			newest, found = ts, true
		}
	}
	return newest, found, nil
}

// writeTrimmed makes a database holding the small tables whole and the last days days of the sample
// tables, capped as a phone caps them.
func writeTrimmed(replicaPath, destPath string, days int) error {
	out, err := sqlite.OpenConn(destPath, sqlite.OpenReadWrite|sqlite.OpenCreate|sqlite.OpenURI)
	if err != nil {
		return err
	}
	defer out.Close()
	out.SetBusyTimeout(busyTimeout)
	source := fileURI(replicaPath, "ro")
	if err := exec(out, "ATTACH DATABASE ? AS src", source); err != nil {
		return err
	}
	pageSize, _, err := oneInt(out, "PRAGMA src.page_size")
	if err != nil {
		return err
	}
	autoVacuum, _, _ := oneInt(out, "PRAGMA src.auto_vacuum")
	userVersion, _, _ := oneInt(out, "PRAGMA src.user_version")
	var statements []string
	err = run(out, "SELECT sql FROM src.sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' "+
		"ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, rowid", nil, func(row *sqlite.Stmt) error {
		statements = append(statements, row.ColumnText(0))
		return nil
	})
	if err != nil {
		return err
	}
	// The page size has to be set before anything is written, and so before the source is attached again.
	for _, step := range []string{"DETACH DATABASE src", fmt.Sprintf("PRAGMA page_size = %d", pageSize), fmt.Sprintf("PRAGMA auto_vacuum = %d", autoVacuum)} {
		if err := exec(out, step); err != nil {
			return err
		}
	}
	for _, statement := range statements {
		if err := exec(out, statement); err != nil {
			return err
		}
	}
	if err := exec(out, "ATTACH DATABASE ? AS src", source); err != nil {
		return err
	}
	known, err := shapeOf(out, "main")
	if err != nil {
		return err
	}
	// One read of the copy from start to end, so the backup is one moment of it.
	if err := exec(out, "BEGIN"); err != nil {
		return err
	}
	err = func() error {
		newest, found, err := newestTS(out, known, "src")
		if err != nil {
			return err
		}
		for _, table := range known.names {
			cols := known.Tables[table]
			names := columnList(cols)
			copyRows := fmt.Sprintf("INSERT INTO main.%s (%s) SELECT %s FROM src.%s", quote(table), names, names, quote(table))
			if isSample(cols) && found {
				err = exec(out, copyRows+" WHERE "+quote(tsCol)+" >= ?", newest-int64(days)*86_400)
			} else {
				err = exec(out, copyRows)
			}
			if err != nil {
				return err
			}
		}
		for _, table := range sortedKeys(caps) {
			if _, ok := known.Tables[table]; !ok {
				continue
			}
			var devices []string
			if err := run(out, fmt.Sprintf("SELECT DISTINCT %s FROM main.%s", quote(deviceCol), quote(table)), nil, func(row *sqlite.Stmt) error {
				devices = append(devices, row.ColumnText(0))
				return nil
			}); err != nil {
				return err
			}
			for _, device := range devices {
				// The core's own statement, WhoopDao.pruneV18Aux and prunePpgWaveform.
				err := exec(out, fmt.Sprintf("DELETE FROM %s WHERE deviceId = ? AND ts < "+
					"(SELECT MIN(ts) FROM (SELECT ts FROM %s WHERE deviceId = ? ORDER BY ts DESC LIMIT ?))", quote(table), quote(table)),
					device, device, caps[table])
				if err != nil {
					return err
				}
			}
		}
		return exec(out, "COMMIT")
	}()
	if err != nil {
		exec(out, "ROLLBACK")
		return err
	}
	if err := exec(out, "DETACH DATABASE src"); err != nil {
		return err
	}
	return exec(out, fmt.Sprintf("PRAGMA user_version = %d", userVersion))
}

// holdStill holds a read lock on the copy until the connection is closed. While it is held nothing can
// change the file.
func holdStill(replicaPath string) (*sqlite.Conn, error) {
	conn, err := connect(replicaPath, "ro")
	if err != nil {
		return nil, err
	}
	if err := exec(conn, "BEGIN"); err == nil {
		_, _, err = oneInt(conn, "SELECT count(*) FROM sqlite_master")
	}
	if err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

// writeBackup writes a `.lhoopbak` as the app's own export does: the database, the settings when there
// are any, the manifest.
func writeBackup(out io.Writer, sqlitePath, settingsJSON string, manifest map[string]any) error {
	archive := zip.NewWriter(out)
	archive.RegisterCompressor(zip.Deflate, func(w io.Writer) (io.WriteCloser, error) {
		return flate.NewWriter(w, 6)
	})
	database, err := os.Open(sqlitePath)
	if err != nil {
		return err
	}
	defer database.Close()
	entry, err := archive.CreateHeader(&zip.FileHeader{Name: dbEntry, Method: zip.Deflate})
	if err != nil {
		return err
	}
	if _, err := io.Copy(entry, database); err != nil {
		return err
	}
	if settingsJSON != "" {
		entry, err := archive.CreateHeader(&zip.FileHeader{Name: settingsEntry, Method: zip.Deflate})
		if err != nil {
			return err
		}
		if _, err := io.WriteString(entry, settingsJSON); err != nil {
			return err
		}
	}
	entry, err = archive.CreateHeader(&zip.FileHeader{Name: manifestEntry, Method: zip.Deflate})
	if err != nil {
		return err
	}
	encoded, err := json.Marshal(manifest) // keys in order, as the app's own manifest has them
	if err != nil {
		return err
	}
	if _, err := entry.Write(encoded); err != nil {
		return err
	}
	return archive.Close()
}
