package backup

// What the app does, written in Go.
//
// It is the phone in this folder's tests, and a way to fill the server from a backup on the Mac
// (`lhoop-backup push`). The app's own code is Kotlin (fork/app/backup); this follows the same steps.
// It reads the database it is given and never writes to it.

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"time"

	"zombiezen.com/go/sqlite"
)

const hoursPerDelta = 24

// ServerSaidNo is the server answering, and the answer being no.
type ServerSaidNo struct {
	Status  int
	Message string
}

func (e *ServerSaidNo) Error() string { return fmt.Sprintf("%d: %s", e.Status, e.Message) }

// Report is what one run did.
type Report struct {
	SchemaCreated bool
	HoursWanted   int
	Deltas        int
	BytesSent     int64
	Verified      bool // the server holds every hour the run looked at
}

type api struct {
	url, token string
	client     *http.Client
}

func (a *api) call(method, path string, body io.Reader, size int64, headers map[string]string) (map[string]any, error) {
	request, err := http.NewRequest(method, a.url+path, body)
	if err != nil {
		return nil, err
	}
	request.ContentLength = size
	request.Header.Set("Authorization", "Bearer "+a.token)
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response, err := a.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	var answer map[string]any
	decoder := json.NewDecoder(response.Body)
	decoder.UseNumber()
	decodeErr := decoder.Decode(&answer)
	if response.StatusCode >= 300 {
		message, _ := answer["error"].(string)
		return nil, &ServerSaidNo{response.StatusCode, message}
	}
	return answer, decodeErr
}

func (a *api) json(method, path string, body any) (map[string]any, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	return a.call(method, path, bytes.NewReader(raw), int64(len(raw)), map[string]string{"Content-Type": "application/json"})
}

func (a *api) delta(gzPath string) (map[string]any, error) {
	sha, err := sha256Of(gzPath)
	if err != nil {
		return nil, err
	}
	f, err := os.Open(gzPath)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	return a.call("POST", "/v1/delta", f, info.Size(), map[string]string{"Content-Type": "application/octet-stream", "X-Lhoop-Sha256": sha})
}

// identityOf is Room's own mark of which schema this is.
func identityOf(conn *sqlite.Conn) (string, error) {
	identity, _, err := oneText(conn, "SELECT identity_hash FROM room_master_table")
	return identity, err
}

func schemaBody(conn *sqlite.Conn) (map[string]any, error) {
	identity, err := identityOf(conn)
	if err != nil {
		return nil, err
	}
	userVersion, _, _ := oneInt(conn, "PRAGMA user_version")
	pageSize, _, _ := oneInt(conn, "PRAGMA page_size")
	autoVacuum, _, _ := oneInt(conn, "PRAGMA auto_vacuum")
	statements := []string{}
	err = run(conn, "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' "+
		"ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, rowid", nil, func(row *sqlite.Stmt) error {
		statements = append(statements, row.ColumnText(0))
		return nil
	})
	return map[string]any{
		"format": Format, "userVersion": userVersion, "identityHash": identity,
		"pageSize": pageSize, "autoVacuum": autoVacuum, "statements": statements,
	}, err
}

// buildDelta writes a delta file holding hours in full and small whole. It returns the checksums of
// what it holds.
//
// The checksums are worked out from the rows in the file, not from the database a second time: the
// database goes on changing while this runs, and what counts is that the file agrees with itself.
func buildDelta(dbPath, dest string, known Shape, hours map[string][]Bucket, small []string, meta map[string]string) (map[string]map[Bucket]string, error) {
	out, err := sqlite.OpenConn(dest, sqlite.OpenReadWrite|sqlite.OpenCreate|sqlite.OpenURI)
	if err != nil {
		return nil, err
	}
	defer out.Close()
	sent := map[string]map[Bucket]string{}
	steps := []string{
		"CREATE TABLE " + deltaMeta + " (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
		"CREATE TABLE " + deltaBucket + " (table_name TEXT NOT NULL, device_id TEXT NOT NULL, hour INTEGER NOT NULL, " +
			"print TEXT NOT NULL, PRIMARY KEY (table_name, device_id, hour))",
		"CREATE TABLE " + deltaSmall + " (table_name TEXT PRIMARY KEY)",
		"BEGIN",
	}
	if err := exec(out, "ATTACH DATABASE ? AS core", fileURI(dbPath, "ro")); err != nil {
		return nil, err
	}
	for _, step := range steps {
		if err := exec(out, step); err != nil {
			return nil, err
		}
	}
	for _, key := range sortedKeys(meta) {
		if err := exec(out, "INSERT INTO "+deltaMeta+" VALUES (?, ?)", key, meta[key]); err != nil {
			return nil, err
		}
	}
	for _, table := range sortedKeys(hours) {
		name := quote(table)
		if err := exec(out, fmt.Sprintf("CREATE TABLE %s AS SELECT * FROM core.%s WHERE 0", name, name)); err != nil {
			return nil, err
		}
		oneHour := fmt.Sprintf("INSERT INTO %s SELECT * FROM core.%s WHERE %s = ? AND %s > ? AND %s < ? AND %s / %d = ?",
			name, name, quote(deviceCol), quote(tsCol), quote(tsCol), quote(tsCol), hourSec)
		for _, bucket := range hours[table] {
			// The range lets SQLite use the key; the division is the rule, and settles the two ends.
			if err := exec(out, oneHour, bucket.Device, (bucket.Hour-1)*hourSec, (bucket.Hour+1)*hourSec, bucket.Hour); err != nil {
				return nil, err
			}
		}
		prints, err := hourPrints(out, "main", table, known.Tables[table], nil)
		if err != nil {
			return nil, err
		}
		sent[table] = prints
		for bucket, print := range prints {
			if err := exec(out, "INSERT INTO "+deltaBucket+" VALUES (?, ?, ?, ?)", table, bucket.Device, bucket.Hour, print); err != nil {
				return nil, err
			}
		}
	}
	for _, table := range small {
		if err := exec(out, fmt.Sprintf("CREATE TABLE %s AS SELECT * FROM core.%s", quote(table), quote(table))); err != nil {
			return nil, err
		}
		if err := exec(out, "INSERT INTO "+deltaSmall+" VALUES (?)", table); err != nil {
			return nil, err
		}
	}
	if err := exec(out, "COMMIT"); err != nil {
		return nil, err
	}
	if err := exec(out, "DETACH DATABASE core"); err != nil {
		return nil, err
	}
	return sent, out.Close()
}

// plan asks the server which hours it wants, one table at a time so no request grows with the years.
func (a *api) plan(head map[string]any, prints map[string]map[Bucket]string) (string, map[string][]Bucket, error) {
	want := map[string][]Bucket{}
	for _, table := range sortedKeys(prints) {
		byDevice := map[string]map[string]string{}
		for bucket, print := range prints[table] {
			if byDevice[bucket.Device] == nil {
				byDevice[bucket.Device] = map[string]string{}
			}
			byDevice[bucket.Device][strconv.FormatInt(bucket.Hour, 10)] = print
		}
		body := map[string]any{"prints": map[string]any{table: byDevice}}
		for key, value := range head {
			body[key] = value
		}
		answer, err := a.json("POST", "/v1/plan", body)
		if err != nil {
			return "", nil, err
		}
		if state, _ := answer["schema"].(string); state != "ok" {
			return state, nil, nil
		}
		wanted, _ := answer["want"].(map[string]any)
		devices, _ := wanted[table].(map[string]any)
		for device, hours := range devices {
			list, _ := hours.([]any)
			for _, hour := range list {
				n, _ := wholeNumber(hour)
				want[table] = append(want[table], Bucket{device, n})
			}
		}
	}
	return "ok", want, nil
}

// SyncOptions say what a run looks at and what it tells the server about the app.
type SyncOptions struct {
	Days         *int // the last this many days of the sample tables; all of them when nil
	AppBuild     string
	AppVersion   string
	SettingsJSON string
}

// Sync is one run: ask what the server wants, send it, and check.
func Sync(dbPath, url, token string, opts SyncOptions) (Report, error) {
	var report Report
	server := &api{url: url, token: token, client: &http.Client{Timeout: 10 * time.Minute}}
	conn, err := sqlite.OpenConn(dbPath, sqlite.OpenReadOnly|sqlite.OpenURI)
	if err != nil {
		return report, err
	}
	defer conn.Close()
	work, err := os.MkdirTemp("", "lhoop-delta-")
	if err != nil {
		return report, err
	}
	defer os.RemoveAll(work)

	known, err := shapeOf(conn, "main")
	if err != nil {
		return report, err
	}
	userVersion, _, _ := oneInt(conn, "PRAGMA user_version")
	identity, err := identityOf(conn)
	if err != nil {
		return report, err
	}
	head := map[string]any{"format": Format, "userVersion": userVersion, "identityHash": identity}
	var floor *int64
	if opts.Days != nil {
		var newest int64
		for _, table := range known.sample() {
			if ts, ok, _ := oneInt(conn, "SELECT max(ts) FROM "+quote(table)); ok && ts > newest {
				newest = ts
			}
		}
		from := newest/hourSec - int64(*opts.Days)*24
		floor = &from
	}
	prints := map[string]map[Bucket]string{}
	for _, table := range known.sample() {
		found, err := hourPrints(conn, "main", table, known.Tables[table], floor)
		if err != nil {
			return report, err
		}
		if len(found) > 0 {
			prints[table] = found
		}
	}
	if len(prints) == 0 { // a database that has recorded nothing has nothing to send
		report.Verified = true
		return report, nil
	}

	state, want, err := server.plan(head, prints)
	if err != nil {
		return report, err
	}
	if state == "missing" {
		body, err := schemaBody(conn)
		if err != nil {
			return report, err
		}
		if _, err := server.json("PUT", "/v1/schema", body); err != nil {
			return report, err
		}
		report.SchemaCreated = true
		if state, want, err = server.plan(head, prints); err != nil {
			return report, err
		}
	}
	if state != "ok" {
		return report, &ServerSaidNo{409, "the server's copy was made for another schema"}
	}

	// A day of hours to a file. The small tables ride in the last one, or alone when nothing else is wanted.
	groups := map[int64]map[string][]Bucket{}
	for table, hours := range want {
		report.HoursWanted += len(hours)
		for _, bucket := range hours {
			day := floorDiv(bucket.Hour, hoursPerDelta)
			if groups[day] == nil {
				groups[day] = map[string][]Bucket{}
			}
			groups[day][table] = append(groups[day][table], bucket)
		}
	}
	days := make([]int64, 0, len(groups))
	for day := range groups {
		days = append(days, day)
	}
	sort.Slice(days, func(i, j int) bool { return days[i] < days[j] })
	files := make([]map[string][]Bucket, 0, len(days)+1)
	for _, day := range days {
		files = append(files, groups[day])
	}
	if len(files) == 0 {
		files = append(files, map[string][]Bucket{})
	}
	meta := map[string]string{
		"format": strconv.Itoa(Format), "user_version": strconv.FormatInt(userVersion, 10), "identity_hash": identity,
		"app_build": opts.AppBuild, "app_version": opts.AppVersion,
	}
	if opts.SettingsJSON != "" {
		meta["settings_json"] = opts.SettingsJSON
	}
	for index, hours := range files {
		plain := filepath.Join(work, fmt.Sprintf("delta-%d.sqlite", index))
		var small []string
		if index == len(files)-1 {
			small = known.small()
		}
		sent, err := buildDelta(dbPath, plain, known, hours, small, meta)
		if err != nil {
			return report, err
		}
		for table, found := range sent {
			for bucket, print := range found {
				prints[table][bucket] = print
			}
		}
		packed := plain + ".gz"
		if err := gzipFile(plain, packed); err != nil {
			return report, err
		}
		if _, err := server.delta(packed); err != nil {
			return report, err
		}
		info, _ := os.Stat(packed)
		report.Deltas++
		report.BytesSent += info.Size()
		os.Remove(plain)
		os.Remove(packed)
	}

	// The same checksums again. Nothing wanted means everything sent is merged.
	state, left, err := server.plan(head, prints)
	if err != nil {
		return report, err
	}
	report.Verified = state == "ok" && len(left) == 0
	return report, nil
}

func floorDiv(a, b int64) int64 {
	q := a / b
	if (a%b != 0) && ((a < 0) != (b < 0)) {
		q--
	}
	return q
}

func gzipFile(from, to string) error {
	in, err := os.Open(from)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(to)
	if err != nil {
		return err
	}
	defer out.Close()
	packer, _ := gzip.NewWriterLevel(out, 6)
	if _, err := io.Copy(packer, in); err != nil {
		return err
	}
	if err := packer.Close(); err != nil {
		return err
	}
	return out.Close()
}
