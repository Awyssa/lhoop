package backup

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/rand"
	"os"
	"strings"
	"testing"
	"unicode/utf8"

	"zombiezen.com/go/sqlite"
)

func memory(t *testing.T) *sqlite.Conn {
	t.Helper()
	conn, err := sqlite.OpenConn(":memory:", sqlite.OpenReadWrite|sqlite.OpenCreate|sqlite.OpenMemory)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

func mustExec(t *testing.T, conn *sqlite.Conn, query string, args ...any) {
	t.Helper()
	if err := exec(conn, query, args...); err != nil {
		t.Fatalf("%s: %v", query, err)
	}
}

func printsOf(t *testing.T, conn *sqlite.Conn, table string) map[Bucket]string {
	t.Helper()
	cols, err := columns(conn, "main", table)
	if err != nil {
		t.Fatal(err)
	}
	prints, err := hourPrints(conn, "main", table, cols, nil)
	if err != nil {
		t.Fatal(err)
	}
	return prints
}

// The cases the app's tests read too. If this passes and the app's BackupChecksumTest passes, the two agree.
func TestTheSharedCasesGiveTheChecksumsWrittenBesideThem(t *testing.T) {
	raw, err := os.ReadFile("../testdata/checksum_cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		Cases []struct {
			Name   string
			Table  string
			Create string
			Rows   [][]any
			Prints map[string]string
		}
	}
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.UseNumber()
	if err := decoder.Decode(&file); err != nil {
		t.Fatal(err)
	}
	isTrue(t, len(file.Cases) >= 4, "the cases are there")
	for _, c := range file.Cases {
		t.Run(c.Name, func(t *testing.T) {
			conn := memory(t)
			mustExec(t, conn, c.Create)
			for _, row := range c.Rows {
				args := make([]any, len(row))
				for i, value := range row {
					switch v := value.(type) {
					case map[string]any:
						blob, err := hex.DecodeString(v["hex"].(string))
						if err != nil {
							t.Fatal(err)
						}
						args[i] = blob
					case json.Number:
						if whole, err := v.Int64(); err == nil {
							args[i] = whole
						} else {
							args[i], _ = v.Float64()
						}
					default:
						args[i] = value
					}
				}
				marks := strings.TrimSuffix(strings.Repeat("?, ", len(row)), ", ")
				mustExec(t, conn, fmt.Sprintf("INSERT INTO `%s` VALUES (%s)", c.Table, marks), args...)
			}
			found := map[string]string{}
			for bucket, print := range printsOf(t, conn, c.Table) {
				found[fmt.Sprintf("%s|%d", bucket.Device, bucket.Hour)] = print
			}
			equal(t, c.Prints, found)
		})
	}
}

func TestTheSQLAgreesWithTheRuleWorkedOutInPlainArithmetic(t *testing.T) {
	rng := rand.New(rand.NewSource(7))
	conn := memory(t)
	mustExec(t, conn, "CREATE TABLE t (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, a INTEGER NOT NULL, b INTEGER, c TEXT, d REAL NOT NULL, PRIMARY KEY (deviceId, ts))")
	texts := []any{nil, "", "abc", "ñandú"}
	flags := []any{nil, int64(1), int64(8)}
	expected := map[Bucket][7]int64{}
	for _, ts := range rng.Perm(5 * 3600)[:400] {
		device := []string{"X", "Y"}[rng.Intn(2)]
		a := int64(rng.Intn(506) - 5)
		b, c := flags[rng.Intn(3)], texts[rng.Intn(4)]
		mustExec(t, conn, "INSERT INTO t VALUES (?, ?, ?, ?, ?, ?)", device, ts, a, b, c, rng.Float64())
		n := expected[Bucket{device, int64(ts / 3600)}]
		n[0]++
		n[1] += int64(ts)
		n[2] += a
		if b != nil {
			n[3] += b.(int64)
			n[4]++
		}
		if c != nil {
			n[5] += int64(utf8.RuneCountInString(c.(string)))
			n[6]++
		}
		expected[Bucket{device, int64(ts / 3600)}] = n
	}
	want := map[Bucket]string{}
	for bucket, n := range expected {
		parts := make([]string, len(n))
		for i, v := range n {
			parts[i] = fmt.Sprint(v)
		}
		want[bucket] = strings.Join(parts, ",")
	}
	equal(t, want, printsOf(t, conn, "t"))
}

func TestTheOrderRowsWereWrittenInDoesNotMatter(t *testing.T) {
	var prints []map[Bucket]string
	for _, backwards := range []bool{false, true} {
		conn := memory(t)
		mustExec(t, conn, "CREATE TABLE t (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, n INTEGER NOT NULL, x REAL NOT NULL, PRIMARY KEY (deviceId, ts))")
		for i := 0; i < 100; i++ {
			ts := 3600 + i
			if backwards {
				ts = 3699 - i
			}
			mustExec(t, conn, "INSERT INTO t VALUES ('A', ?, ?, ?)", ts, ts%9, 0.1*float64(ts))
		}
		prints = append(prints, printsOf(t, conn, "t"))
	}
	equal(t, prints[0], prints[1])
}

func TestATableIsASampleTableOnlyWithTheStrapAndTheSecondInItsKey(t *testing.T) {
	conn := memory(t)
	mustExec(t, conn, "CREATE TABLE s (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL, PRIMARY KEY (deviceId, ts, kind))")
	mustExec(t, conn, "CREATE TABLE day (deviceId TEXT NOT NULL, day TEXT NOT NULL, ts INTEGER, PRIMARY KEY (deviceId, day))")
	mustExec(t, conn, "CREATE TABLE session (deviceId TEXT NOT NULL, startTs INTEGER NOT NULL, PRIMARY KEY (deviceId, startTs))")
	mustExec(t, conn, "CREATE TABLE one (ts INTEGER PRIMARY KEY)")
	for table, want := range map[string]bool{"s": true, "day": false, "session": false, "one": false} {
		cols, err := columns(conn, "main", table)
		if err != nil {
			t.Fatal(err)
		}
		if isSample(cols) != want {
			t.Fatalf("%s: sample table = %v, want %v", table, isSample(cols), want)
		}
	}
}

func TestAColumnMayBeNullOnlyWhenItIsNeitherDeclaredNotNullNorPartOfTheKey(t *testing.T) {
	conn := memory(t)
	mustExec(t, conn, "CREATE TABLE t (deviceId TEXT NOT NULL, ts INTEGER NOT NULL, a INTEGER, b INTEGER NOT NULL, id INTEGER, PRIMARY KEY (deviceId, ts, id))")
	cols, err := columns(conn, "main", "t")
	if err != nil {
		t.Fatal(err)
	}
	found := map[string]bool{}
	for _, c := range cols {
		found[c.Name] = c.Nullable
	}
	equal(t, map[string]bool{"deviceId": false, "ts": false, "a": true, "b": false, "id": false}, found)
}

func TestAChecksumStartsWithItsRowCount(t *testing.T) {
	equal(t, int64(3600), rowsIn("3600,12,9"))
	equal(t, int64(1), rowsIn("1"))
}
