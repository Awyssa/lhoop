package backup

// A made-up phone for the tests: a small database shaped like the app's, a running server, and a way to
// compare.
//
// Every value here is invented. The tables have the app's names and kinds of column, so the tests meet
// the same cases the real schema has: whole numbers, flags that may be null, decimals, text and blobs.

import (
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"sync"
	"testing"

	"zombiezen.com/go/sqlite"
)

const (
	testToken    = "a-made-up-token-for-the-tests"
	testIdentity = "made-up-identity-hash"
	testVersion  = 41
	t0           = 1_900_000_800 // an arbitrary hour boundary in 2030
	hour         = 3600
	day          = 86_400
	strap        = "TESTSTRP"
)

var phoneStatements = []string{
	"CREATE TABLE `hrSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
	"CREATE TABLE `rrInterval` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `rrMs` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `synced` INTEGER NOT NULL, `srcChannel` INTEGER, `ord` INTEGER, `tsSuspect` INTEGER, PRIMARY KEY(`deviceId`, `ts`, `rrMs`, `seq`))",
	"CREATE TABLE `gravitySample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `x` REAL NOT NULL, `y` REAL NOT NULL, `z` REAL NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
	"CREATE TABLE `event` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `kind` TEXT NOT NULL, `payloadJSON` TEXT NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`, `kind`))",
	"CREATE TABLE `v18AuxSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `payload` BLOB NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
	"CREATE TABLE `dailyMetric` (`deviceId` TEXT NOT NULL, `day` TEXT NOT NULL, `restingHr` INTEGER, `hrv` REAL, PRIMARY KEY(`deviceId`, `day`))",
	"CREATE TABLE `pairedDevice` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
	"CREATE INDEX `index_event_kind` ON `event` (`kind`)",
	"CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
	"CREATE TABLE android_metadata (locale TEXT)",
}

// phone is a database the tests write to as the app would, with made-up values that follow from the second.
type phone struct {
	t    *testing.T
	path string
	conn *sqlite.Conn
}

func newPhone(t *testing.T, path string) *phone {
	t.Helper()
	conn, err := sqlite.OpenConn(path, sqlite.OpenReadWrite|sqlite.OpenCreate)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	p := &phone{t, path, conn}
	p.run("PRAGMA auto_vacuum = 1")
	for _, statement := range phoneStatements {
		p.run(statement)
	}
	p.run("PRAGMA user_version = 41")
	p.run("INSERT INTO room_master_table VALUES (42, ?)", testIdentity)
	p.run("INSERT INTO android_metadata VALUES ('en_US')")
	p.run("INSERT INTO pairedDevice VALUES (?, 'a made-up strap')", strap)
	return p
}

func (p *phone) run(query string, args ...any) {
	p.t.Helper()
	if err := exec(p.conn, query, args...); err != nil {
		p.t.Fatalf("%s: %v", query, err)
	}
}

// wear writes one row a second in the one-second tables, a beat on most seconds, an event every ten minutes.
func (p *phone) wear(start, seconds int64) { p.wearAs(strap, start, seconds) }

func (p *phone) wearAs(device string, start, seconds int64) {
	p.t.Helper()
	const each = "WITH RECURSIVE n(ts) AS (SELECT ?1 UNION ALL SELECT ts + 1 FROM n WHERE ts + 1 < ?2) "
	end := start + seconds
	p.run("BEGIN")
	p.run(each+"INSERT INTO hrSample SELECT ?3, ts, 50 + ts % 23, 1 FROM n", start, end, device)
	p.run(each+"INSERT INTO gravitySample SELECT ?3, ts, (ts % 7) / 7.0, 0.1, -0.25, 1 FROM n", start, end, device)
	p.run(each+"INSERT INTO v18AuxSample SELECT ?3, ts, unhex(printf('%02x0309', ts % 251)) FROM n", start, end, device)
	p.run(each+"INSERT INTO rrInterval SELECT ?3, ts, 700 + ts % 300, 0, 1, CASE WHEN ts % 2 = 1 THEN 7 END, NULL, NULL FROM n WHERE ts % 5 != 0", start, end, device)
	p.run(each+"INSERT INTO event SELECT ?3, ts, 'DOUBLE_TAP(14)', '{}', 1 FROM n WHERE ts % 600 = 0", start, end, device)
	p.run("COMMIT")
}

// serverCase is a test with a phone, an empty server on a free port, and both cleared away afterwards.
type serverCase struct {
	t     *testing.T
	dir   string
	store *Store
	url   string
	phone *phone

	mu  sync.Mutex
	log []string
}

func newCase(t *testing.T) *serverCase {
	t.Helper()
	c := &serverCase{t: t, dir: t.TempDir()}
	store, err := NewStore(filepath.Join(c.dir, "data"))
	if err != nil {
		t.Fatal(err)
	}
	c.store = store
	h, err := NewHandler(store, TokenHash(testToken), func(line string) {
		c.mu.Lock()
		c.log = append(c.log, line)
		c.mu.Unlock()
	})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(h)
	t.Cleanup(server.Close)
	c.url = server.URL
	c.phone = newPhone(t, filepath.Join(c.dir, "phone.sqlite"))
	return c
}

func (c *serverCase) sync() Report { return c.syncWith(SyncOptions{}) }

func (c *serverCase) syncWith(opts SyncOptions) Report {
	c.t.Helper()
	report, err := Sync(c.phone.path, c.url, testToken, opts)
	if err != nil {
		c.t.Fatalf("the run failed: %v", err)
	}
	return report
}

func (c *serverCase) differing() []string {
	c.t.Helper()
	differ, err := DifferingTables(c.phone.path, c.store.ReplicaPath)
	if err != nil {
		c.t.Fatal(err)
	}
	return differ
}

func (c *serverCase) assertCopyIsThePhone() {
	c.t.Helper()
	if differ := c.differing(); len(differ) != 0 {
		c.t.Fatalf("the copy differs from the phone in %v", differ)
	}
}

// count is the number of rows of the copy's table that match where.
func (c *serverCase) count(table, where string, args ...any) int64 {
	c.t.Helper()
	return countIn(c.t, c.store.ReplicaPath, table, where, args...)
}

func countIn(t *testing.T, path, table, where string, args ...any) int64 {
	t.Helper()
	conn, err := sqlite.OpenConn(path, sqlite.OpenReadOnly)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if where == "" {
		where = "1"
	}
	n, _, err := oneInt(conn, "SELECT count(*) FROM "+table+" WHERE "+where, args...)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func (c *serverCase) ledger() *Ledger {
	c.t.Helper()
	ledger, err := openLedger(c.store.LedgerPath)
	if err != nil {
		c.t.Fatal(err)
	}
	c.t.Cleanup(ledger.Close)
	return ledger
}

func equal(t *testing.T, want, got any) {
	t.Helper()
	if !reflect.DeepEqual(want, got) {
		t.Fatalf("\nwant: %#v\n got: %#v", want, got)
	}
}

func isTrue(t *testing.T, ok bool, what string) {
	t.Helper()
	if !ok {
		t.Fatal(what)
	}
}

func intPtr(n int) *int { return &n }
