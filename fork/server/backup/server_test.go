package backup

// What the server refuses, and that nothing it has can be read back through it.

import (
	"bytes"
	"compress/gzip"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"zombiezen.com/go/sqlite"
)

var testHead = map[string]any{"format": 1, "userVersion": testVersion, "identityHash": testIdentity}

func with(base map[string]any, more map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range base {
		out[k] = v
	}
	for k, v := range more {
		out[k] = v
	}
	return out
}

// raw sends one request and returns the status and the body. token "" sends no Authorization header.
func (c *serverCase) raw(method, path string, body []byte, headers map[string]string, token string) (int, string) {
	c.t.Helper()
	request, err := http.NewRequest(method, c.url+path, bytes.NewReader(body))
	if err != nil {
		c.t.Fatal(err)
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		c.t.Fatal(err)
	}
	defer response.Body.Close()
	said, _ := io.ReadAll(response.Body)
	return response.StatusCode, string(said)
}

func (c *serverCase) postJSON(method, path string, body any) (int, map[string]any) {
	c.t.Helper()
	encoded, _ := json.Marshal(body)
	status, said := c.raw(method, path, encoded, map[string]string{"Content-Type": "application/json"}, testToken)
	var answer map[string]any
	json.Unmarshal([]byte(said), &answer)
	return status, answer
}

func (c *serverCase) makeSchema() (int, map[string]any) {
	c.t.Helper()
	conn, err := sqlite.OpenConn(c.phone.path, sqlite.OpenReadOnly)
	if err != nil {
		c.t.Fatal(err)
	}
	defer conn.Close()
	body, err := schemaBody(conn)
	if err != nil {
		c.t.Fatal(err)
	}
	return c.postJSON("PUT", "/v1/schema", body)
}

// delta makes a delta file of every hour the phone holds, changed by tamper before it is packed. It
// returns the gzip's path.
func (c *serverCase) delta(tamper func(*sqlite.Conn) error, small []string, meta map[string]string) string {
	c.t.Helper()
	conn, err := sqlite.OpenConn(c.phone.path, sqlite.OpenReadOnly)
	if err != nil {
		c.t.Fatal(err)
	}
	known, err := shapeOf(conn, "main")
	if err != nil {
		c.t.Fatal(err)
	}
	hours := map[string][]Bucket{}
	for _, table := range known.sample() {
		prints, err := hourPrints(conn, "main", table, known.Tables[table], nil)
		if err != nil {
			c.t.Fatal(err)
		}
		for bucket := range prints {
			hours[table] = append(hours[table], bucket)
		}
	}
	conn.Close()
	entries, _ := os.ReadDir(c.dir)
	plain := filepath.Join(c.dir, fmt.Sprintf("delta-%d.sqlite", len(entries)))
	full := map[string]string{"format": "1", "user_version": fmt.Sprint(testVersion), "identity_hash": testIdentity, "app_build": "test"}
	for k, v := range meta {
		full[k] = v
	}
	if _, err := buildDelta(c.phone.path, plain, known, hours, small, full); err != nil {
		c.t.Fatal(err)
	}
	if tamper != nil {
		file, err := sqlite.OpenConn(plain, sqlite.OpenReadWrite)
		if err != nil {
			c.t.Fatal(err)
		}
		if err := tamper(file); err != nil {
			c.t.Fatalf("the tamper itself failed: %v", err)
		}
		file.Close()
	}
	if err := gzipFile(plain, plain+".gz"); err != nil {
		c.t.Fatal(err)
	}
	return plain + ".gz"
}

func (c *serverCase) send(gzPath string) (map[string]any, error) {
	return (&api{url: c.url, token: testToken, client: http.DefaultClient}).delta(gzPath)
}

func (c *serverCase) assertRefused(status int, gzPath string) {
	c.t.Helper()
	_, err := c.send(gzPath)
	var no *ServerSaidNo
	if !errors.As(err, &no) {
		c.t.Fatalf("the upload was not refused: %v", err)
	}
	if no.Status != status {
		c.t.Fatalf("refused with %d (%s), want %d", no.Status, no.Message, status)
	}
	if n := c.count("hrSample", ""); n != 0 {
		c.t.Fatalf("a refused upload must leave the copy as it was; it holds %d rows", n)
	}
	equal(c.t, []string{}, namesIn(c.t, c.store.IncomingDir))
}

func sql(statement string, args ...any) func(*sqlite.Conn) error {
	return func(conn *sqlite.Conn) error { return exec(conn, statement, args...) }
}

// Who may ask

func TestTheHealthCheckNeedsNoTokenAndSaysNothing(t *testing.T) {
	c := newCase(t)
	status, said := c.raw("GET", "/healthz", nil, nil, "")
	equal(t, 200, status)
	equal(t, "ok\n", said)
}

func TestNothingElseAnswersWithoutTheToken(t *testing.T) {
	c := newCase(t)
	for _, route := range [][2]string{{"POST", "/v1/plan"}, {"POST", "/v1/delta"}, {"PUT", "/v1/schema"}, {"GET", "/v1/anything"}, {"GET", "/"}} {
		for _, token := range []string{"", "not-the-token"} {
			status, said := c.raw(route[0], route[1], []byte("{}"), nil, token)
			if status != 401 || strings.TrimSpace(said) != `{"error":"unauthorised"}` {
				t.Fatalf("%s %s with token %q: %d %s", route[0], route[1], token, status, said)
			}
		}
	}
	status, _ := c.raw("POST", "/v1/plan", []byte("{}"), map[string]string{"Authorization": "Basic " + testToken}, "")
	equal(t, 401, status)
}

func TestNoRouteReturnsDataEvenWithTheToken(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 120)
	c.sync()
	for _, path := range []string{"/v1/delta", "/v1/plan", "/v1/schema", "/v1/backup", "/v1/export", "/replica.sqlite", "/data/replica.sqlite", "/log/"} {
		if status, _ := c.raw("GET", path, nil, nil, testToken); status != 404 {
			t.Fatalf("GET %s answered %d", path, status)
		}
	}
}

func TestTheLogLineCarriesNoTokenAndNoQuery(t *testing.T) {
	c := newCase(t)
	c.raw("POST", "/v1/plan?x="+testToken, []byte("{}"), map[string]string{"Content-Type": "application/json"}, testToken)
	c.mu.Lock()
	defer c.mu.Unlock()
	isTrue(t, len(c.log) > 0, "a line was logged")
	for _, line := range c.log {
		if strings.Contains(line, testToken) || strings.Contains(line, "?") {
			t.Fatalf("the log line gives too much away: %s", line)
		}
	}
}

// The shape of a request

func (c *serverCase) statusLine(request string) string {
	c.t.Helper()
	conn, err := net.Dial("tcp", strings.TrimPrefix(c.url, "http://"))
	if err != nil {
		c.t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte(request)); err != nil {
		c.t.Fatal(err)
	}
	buffer := make([]byte, 4096)
	n, _ := conn.Read(buffer)
	line, _, _ := strings.Cut(string(buffer[:n]), "\r\n")
	return line
}

func TestARequestMustSayHowLongItIs(t *testing.T) {
	c := newCase(t)
	line := c.statusLine("POST /v1/plan HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + testToken + "\r\n\r\n")
	isTrue(t, strings.Contains(line, " 411 "), line)
	chunked := c.statusLine("POST /v1/delta HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + testToken +
		"\r\nTransfer-Encoding: chunked\r\nX-Lhoop-Sha256: " + strings.Repeat("0", 64) + "\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
	isTrue(t, strings.Contains(chunked, " 411 "), chunked)
}

func TestARequestTooLargeIsRefusedBeforeItIsRead(t *testing.T) {
	c := newCase(t)
	line := c.statusLine(fmt.Sprintf("POST /v1/delta HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer %s\r\nContent-Length: %d\r\nX-Lhoop-Sha256: %s\r\n\r\n",
		testToken, 65<<20, strings.Repeat("0", 64)))
	isTrue(t, strings.Contains(line, " 413 "), line)
}

func TestABodyThatIsNotJSONIsRefused(t *testing.T) {
	c := newCase(t)
	for _, body := range []string{"not json", "[1, 2]", `{"format": `} {
		if status, _ := c.raw("POST", "/v1/plan", []byte(body), nil, testToken); status != 400 {
			t.Fatalf("%q answered %d", body, status)
		}
	}
}

func TestAnUploadMustCarryItsOwnChecksumAndMatchIt(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	c.phone.wear(t0, 60)
	packed, err := os.ReadFile(c.delta(nil, nil, nil))
	if err != nil {
		t.Fatal(err)
	}
	status, _ := c.raw("POST", "/v1/delta", packed, nil, testToken)
	equal(t, 400, status)
	status, _ = c.raw("POST", "/v1/delta", packed, map[string]string{"X-Lhoop-Sha256": strings.Repeat("0", 64)}, testToken)
	equal(t, 400, status)
	equal(t, int64(0), c.count("hrSample", ""))
	path := filepath.Join(c.dir, "right.gz")
	os.WriteFile(path, packed, 0o600)
	sum, _ := sha256Of(path)
	status, _ = c.raw("POST", "/v1/delta", packed, map[string]string{"X-Lhoop-Sha256": sum}, testToken)
	equal(t, 200, status)
	equal(t, int64(60), c.count("hrSample", ""))
}

// The schema

func TestTheSchemaIsTakenOnce(t *testing.T) {
	c := newCase(t)
	status, answer := c.makeSchema()
	equal(t, 201, status)
	equal(t, map[string]any{"schema": "created", "tables": float64(9)}, answer)
	status, _ = c.makeSchema()
	equal(t, 409, status)
}

func TestASchemaMayOnlyCreateTablesAndIndexes(t *testing.T) {
	c := newCase(t)
	good := "CREATE TABLE s (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (deviceId, ts))"
	for _, bad := range []any{
		"CREATE TRIGGER t AFTER INSERT ON s BEGIN DELETE FROM s; END",
		"CREATE VIEW v AS SELECT * FROM s",
		"CREATE TABLE copy AS SELECT * FROM s",
		"CREATE TABLE a (x); DROP TABLE s",
		"DROP TABLE s",
		"ATTACH DATABASE '/tmp/x' AS other",
		"PRAGMA writable_schema = ON",
		"INSERT INTO s VALUES ('a', 1)",
		"CREATE VIRTUAL TABLE f USING fts5(x)",
		"CREATE INDEX i ON s (load_extension('x'))",
		7,
	} {
		status, _ := c.postJSON("PUT", "/v1/schema", with(testHead, map[string]any{"statements": []any{good, bad}}))
		if status != 422 {
			t.Fatalf("%v answered %d", bad, status)
		}
		for _, name := range namesIn(t, c.store.Dir) {
			if strings.HasPrefix(name, "replica") {
				t.Fatalf("%v left %s behind", bad, name)
			}
		}
	}
	// And the honest schema still goes in afterwards.
	status, _ := c.postJSON("PUT", "/v1/schema", with(testHead, map[string]any{"statements": []any{good}}))
	equal(t, 201, status)
}

func TestASchemaNeedsAVersionAnIdentityAndASampleTable(t *testing.T) {
	c := newCase(t)
	good := []any{"CREATE TABLE s (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (deviceId, ts))"}
	cases := []struct {
		status int
		body   map[string]any
	}{
		{400, map[string]any{"format": 1, "identityHash": "x", "statements": good}},
		{400, map[string]any{"format": 1, "userVersion": 41, "statements": good}},
		{400, map[string]any{"format": 1, "userVersion": true, "identityHash": "x", "statements": good}},
		{400, with(testHead, map[string]any{"format": 2, "statements": good})},
		{422, with(testHead, map[string]any{"statements": []any{}})},
		{422, with(testHead, map[string]any{"statements": []any{"CREATE TABLE only_small (id INTEGER PRIMARY KEY)"}})},
		{422, with(testHead, map[string]any{"pageSize": 1000, "statements": good})},
	}
	for _, each := range cases {
		if status, answer := c.postJSON("PUT", "/v1/schema", each.body); status != each.status {
			t.Fatalf("%v answered %d %v, want %d", each.body, status, answer, each.status)
		}
	}
}

// The plan

func TestAPlanBeforeAnySchemaSaysTheCopyIsMissing(t *testing.T) {
	c := newCase(t)
	status, answer := c.postJSON("POST", "/v1/plan", with(testHead, map[string]any{"prints": map[string]any{}}))
	equal(t, 200, status)
	equal(t, map[string]any{"schema": "missing", "want": map[string]any{}}, answer)
}

func TestAPlanForAnotherSchemaSaysSoAndWantsNothing(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	prints := map[string]any{"hrSample": map[string]any{strap: map[string]any{"1": "1,2,3,4"}}}
	_, answer := c.postJSON("POST", "/v1/plan", with(testHead, map[string]any{"identityHash": "another", "prints": prints}))
	equal(t, map[string]any{"schema": "mismatch", "want": map[string]any{}}, answer)
	_, answer = c.postJSON("POST", "/v1/plan", with(testHead, map[string]any{"userVersion": 42, "prints": map[string]any{}}))
	equal(t, "mismatch", answer["schema"])
}

func TestAPlanWantsEveryHourItHasNotMerged(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	prints := map[string]any{"hrSample": map[string]any{strap: map[string]any{"9": "1,2,3,4", "8": "1,2,3,4"}}}
	status, answer := c.postJSON("POST", "/v1/plan", with(testHead, map[string]any{"prints": prints}))
	equal(t, 200, status)
	equal(t, map[string]any{"schema": "ok", "want": map[string]any{"hrSample": map[string]any{strap: []any{float64(8), float64(9)}}}}, answer)
}

func TestAPlanForATableThatIsNotASampleTableIsRefused(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	for _, prints := range []map[string]any{
		{"dailyMetric": map[string]any{strap: map[string]any{"1": "1"}}},
		{"nothing": map[string]any{strap: map[string]any{"1": "1"}}},
		{"sqlite_master": map[string]any{strap: map[string]any{"1": "1"}}},
		{"hrSample": map[string]any{strap: map[string]any{"soon": "1"}}},
		{"hrSample": []any{1}},
	} {
		if status, _ := c.postJSON("POST", "/v1/plan", with(testHead, map[string]any{"prints": prints})); status != 400 {
			t.Fatalf("%v answered %d", prints, status)
		}
	}
	status, _ := c.postJSON("POST", "/v1/plan", testHead) // no checksums at all
	equal(t, 400, status)
}

// The delta

func TestAnUploadBeforeAnySchemaIsRefused(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 60)
	_, err := c.send(c.delta(nil, nil, nil))
	var no *ServerSaidNo
	isTrue(t, errors.As(err, &no) && no.Status == 409, fmt.Sprint(err))
}

func TestASoundUploadIsMergedAndTheSameFileAgainChangesNothing(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	c.phone.wear(t0, hour+60)
	path := c.delta(nil, []string{"dailyMetric", "pairedDevice", "room_master_table", "android_metadata"}, nil)
	packed, _ := os.ReadFile(path)
	again := path + ".again"
	os.WriteFile(again, packed, 0o600)
	first, err := c.send(path)
	if err != nil {
		t.Fatal(err)
	}
	equal(t, false, first["again"])
	equal(t, json.Number("10"), first["hours"])
	second, err := c.send(again)
	if err != nil {
		t.Fatal(err)
	}
	equal(t, true, second["again"])
	equal(t, first["upload"], second["upload"])
	c.assertCopyIsThePhone()
	summary, _ := c.ledger().summary()
	equal(t, int64(2), summary.Merged) // the schema and one delta
}

func TestWhatDoesNotAgreeWithItselfIsRefusedWhole(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	c.phone.wear(t0, 120)
	tampers := []struct {
		name   string
		tamper func(*sqlite.Conn) error
	}{
		{"a value changed after the checksums were made", sql("UPDATE hrSample SET bpm = bpm + 1 WHERE ts = ?", t0+3)},
		{"a row dropped", sql("DELETE FROM gravitySample WHERE ts = ?", t0+3)},
		{"a row in an hour that is not listed", sql("INSERT INTO hrSample VALUES (?, ?, 60, 1)", strap, t0+9*hour)},
		{"an hour listed with no rows", sql("INSERT INTO "+deltaBucket+" VALUES ('hrSample', ?, 5, '1,2,3,4')", strap)},
		{"a table the copy does not have", sql("CREATE TABLE extra (x)")},
		{"a column the copy does not have", sql("ALTER TABLE hrSample ADD COLUMN more INTEGER")},
		{"a view", sql("CREATE VIEW v AS SELECT 1")},
		{"a virtual table", sql("CREATE VIRTUAL TABLE extra USING fts5(x)")},
		{"a trigger", sql("CREATE TRIGGER t AFTER INSERT ON hrSample BEGIN SELECT 1; END")},
		{"a small table sent as hours", sql("INSERT INTO "+deltaBucket+" VALUES ('dailyMetric', ?, 5, '1')", strap)},
		{"a sample table sent whole", sql("INSERT INTO " + deltaSmall + " VALUES ('hrSample')")},
		{"a table sent but not listed", sql("CREATE TABLE dailyMetric (deviceId, day, restingHr, hrv)")},
		{"another format", sql("UPDATE " + deltaMeta + " SET value = '2' WHERE key = 'format'")},
		{"no list of hours", sql("DROP TABLE " + deltaBucket)},
		{"a list of hours with a number for a strap", sql("INSERT INTO " + deltaBucket + " VALUES ('hrSample', 7, 5, '1')")},
	}
	for _, each := range tampers {
		t.Run(each.name, func(t *testing.T) {
			c.t = t
			c.assertRefused(422, c.delta(each.tamper, nil, nil))
		})
	}
	c.t = t
	summary, _ := c.ledger().summary()
	equal(t, int64(len(tampers)), summary.Refused)
	// The server is none the worse for it: the honest file still goes in.
	if _, err := c.send(c.delta(nil, nil, nil)); err != nil {
		t.Fatal(err)
	}
	equal(t, int64(120), c.count("hrSample", ""))
}

func TestAnUploadForAnotherSchemaIsRefused(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	c.phone.wear(t0, 60)
	c.assertRefused(409, c.delta(nil, nil, map[string]string{"identity_hash": "another"}))
	c.assertRefused(409, c.delta(nil, nil, map[string]string{"user_version": "42"}))
}

func TestARowThatBreaksItsTableIsRefusedAndNothingOfTheUploadIsKept(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	c.phone.wear(t0, 60)
	c.phone.run("INSERT INTO dailyMetric VALUES ('TESTSTRP', '2030-03-01', 50, 61.5)")
	c.assertRefused(422, c.delta(sql("UPDATE dailyMetric SET day = NULL"), []string{"dailyMetric"}, nil))
	equal(t, int64(0), c.count("dailyMetric", ""))
}

func gzipped(data []byte) []byte {
	var out bytes.Buffer
	w := gzip.NewWriter(&out)
	w.Write(data)
	w.Close()
	return out.Bytes()
}

func TestWhatIsNotADeltaFileIsRefused(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	noise := make([]byte, 5000)
	rand.Read(noise)
	cases := map[string][]byte{
		"not gzip":               []byte("plain bytes"),
		"gzip of something else": gzipped([]byte("hello")),
		"half a gzip":            gzipped(noise)[:100],
		"gzip of nothing":        gzipped(nil),
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			c.t = t
			path := filepath.Join(c.dir, "junk.gz")
			os.WriteFile(path, body, 0o600)
			c.assertRefused(422, path)
		})
	}
}

func TestAnUploadThatUnpacksTooLargeIsCutOff(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	c.phone.wear(t0, 600)
	before := maxUnpacked
	maxUnpacked = 4096
	defer func() { maxUnpacked = before }()
	c.assertRefused(413, c.delta(nil, nil, nil))
}

func TestARefusedFileCanBeSentAgainOnceTheReasonIsGone(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	c.phone.wear(t0, 60)
	path := c.delta(nil, nil, nil)
	packed, _ := os.ReadFile(path)
	again := path + ".again"
	os.WriteFile(again, packed, 0o600)
	before := diskMargin
	diskMargin = 1 << 60 // no disk is this free
	c.assertRefused(507, path)
	diskMargin = before
	answer, err := c.send(again)
	if err != nil {
		t.Fatal(err)
	}
	equal(t, false, answer["again"])
	equal(t, int64(60), c.count("hrSample", ""))
	summary, _ := c.ledger().summary()
	equal(t, int64(0), summary.Refused)
}

// The server died after writing the copy and before writing the ledger.
func TestAMergeTheLedgerNeverHeardOfIsSimplyDoneAgain(t *testing.T) {
	c := newCase(t)
	c.makeSchema()
	c.phone.wear(t0, 300)
	plain := filepath.Join(c.dir, "direct.sqlite")
	if _, err := gunzipTo(c.delta(nil, nil, nil), plain, 1<<30); err != nil {
		t.Fatal(err)
	}
	conn, err := connect(c.store.ReplicaPath, "rw")
	if err != nil {
		t.Fatal(err)
	}
	_, err = merge(conn, plain, testVersion, testIdentity)
	conn.Close()
	if err != nil {
		t.Fatal(err)
	}
	equal(t, int64(300), c.count("hrSample", ""))
	report := c.sync()
	equal(t, 5, report.HoursWanted)
	isTrue(t, report.Verified, "verified")
	c.assertCopyIsThePhone()
}
