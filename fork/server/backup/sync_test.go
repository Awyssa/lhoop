package backup

// Whole runs against a running server: what is sent, what is not, and whether the copy ends up equal to
// the phone.

import (
	"os"
	"path/filepath"
	"sort"
	"testing"

	"zombiezen.com/go/sqlite"
)

func TestAFirstRunMakesTheCopyAndSendsEverything(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 2*hour+300)
	report := c.sync()
	isTrue(t, report.SchemaCreated, "the schema was sent")
	isTrue(t, report.Verified, "the server confirmed the run")
	equal(t, 1, report.Deltas)
	c.assertCopyIsThePhone()
}

func TestAPhoneThatHasRecordedNothingSendsNothing(t *testing.T) {
	c := newCase(t)
	report := c.sync()
	equal(t, Report{Verified: true}, report)
	isTrue(t, !exists(c.store.ReplicaPath), "no copy was made")
}

func TestASecondRunFindsNothingWanted(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, hour)
	c.sync()
	report := c.sync()
	isTrue(t, !report.SchemaCreated, "the schema is sent once")
	equal(t, 0, report.HoursWanted)
	equal(t, 1, report.Deltas) // the small tables alone
	isTrue(t, report.Verified, "verified")
	c.assertCopyIsThePhone()
}

func TestOnlyTheHoursThatAreNewAreSent(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, hour)
	c.sync()
	c.phone.wear(t0+hour, 1800)
	report := c.sync()
	equal(t, 5, report.HoursWanted) // one new hour in each of the five sample tables
	isTrue(t, report.Verified, "verified")
	c.assertCopyIsThePhone()
}

func TestAnHourStillFillingIsSentAgainWithItsNewRows(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 600)
	c.sync()
	c.phone.wear(t0+600, 600)
	equal(t, 5, c.sync().HoursWanted)
	c.assertCopyIsThePhone()
}

func TestAFlagChangedInPlaceIsNoticedAndCarriedOver(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 2*hour)
	c.sync()
	c.phone.run("UPDATE rrInterval SET tsSuspect = 1, srcChannel = 5 WHERE ts = ?", t0+7)
	report := c.sync()
	equal(t, 1, report.HoursWanted)
	isTrue(t, report.Verified, "verified")
	c.assertCopyIsThePhone()
	equal(t, int64(1), c.count("rrInterval", "tsSuspect = 1"))
}

func TestRowsThePhoneDroppedStayOnTheServerAndAreNotAskedForAgain(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 3*hour)
	c.sync()
	// The phone keeps only the newest rows of this table, as the core does.
	c.phone.run("DELETE FROM v18AuxSample WHERE ts < ?", t0+hour+1800)
	report := c.sync()
	equal(t, 1, report.HoursWanted) // the hour the cut runs through; the emptied hour is not listed
	isTrue(t, report.Verified, "verified")
	equal(t, int64(3*hour), c.count("v18AuxSample", ""))
	equal(t, []string{"v18AuxSample"}, c.differing())
	equal(t, 0, c.sync().HoursWanted)
}

func TestASmallTableIsReplacedWhole(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 60)
	c.phone.run("INSERT INTO dailyMetric VALUES ('TESTSTRP', '2030-03-01', 50, 61.5)")
	c.phone.run("INSERT INTO dailyMetric VALUES ('TESTSTRP', '2030-03-02', 52, NULL)")
	c.sync()
	equal(t, int64(2), c.count("dailyMetric", ""))
	c.phone.run("DELETE FROM dailyMetric WHERE day = '2030-03-01'")
	c.phone.run("UPDATE dailyMetric SET restingHr = 49 WHERE day = '2030-03-02'")
	c.sync()
	equal(t, int64(1), c.count("dailyMetric", "restingHr = 49"))
	c.assertCopyIsThePhone()
}

func TestARunOverTheLastDaysLeavesOlderHoursForAFullRun(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, hour)
	c.phone.wear(t0+30*day, hour)
	report := c.syncWith(SyncOptions{Days: intPtr(15)})
	isTrue(t, report.Verified, "verified")
	equal(t, int64(hour), c.count("hrSample", ""))
	c.sync()
	c.assertCopyIsThePhone()
}

func TestTwoStrapsAreKeptApart(t *testing.T) {
	c := newCase(t)
	c.phone.wearAs("STRAPONE", t0, 900)
	c.phone.wearAs("STRAPTWO", t0, 900)
	isTrue(t, c.sync().Verified, "verified")
	c.assertCopyIsThePhone()
	equal(t, int64(900), c.count("hrSample", "deviceId = 'STRAPTWO'"))
}

func TestALongFirstRunGoesUpADayToAFile(t *testing.T) {
	c := newCase(t)
	for _, n := range []int64{0, 2, 5} {
		c.phone.wear(t0+n*day, 300)
	}
	report := c.sync()
	equal(t, 3, report.Deltas)
	isTrue(t, report.Verified, "verified")
	c.assertCopyIsThePhone()
}

func TestEveryMergedUploadIsKeptInTheLogAndNothingIsLeftArriving(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 300)
	c.sync()
	c.sync()
	uploads, err := c.ledger().mergedUploads()
	if err != nil {
		t.Fatal(err)
	}
	var kinds, logged []string
	for _, upload := range uploads {
		kinds = append(kinds, upload.Kind)
		logged = append(logged, upload.LogFile)
	}
	equal(t, []string{"schema", "delta", "delta"}, kinds)
	sort.Strings(logged)
	equal(t, logged, namesIn(t, c.store.LogDir))
	equal(t, []string{}, namesIn(t, c.store.IncomingDir))
}

func namesIn(t *testing.T, dir string) []string {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	names := []string{}
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	sort.Strings(names)
	return names
}

func TestStartingAgainClearsWhatAnInterruptedUploadLeftAndKeepsTheCopy(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 300)
	c.sync()
	for _, folder := range []string{c.store.IncomingDir, c.store.TmpDir} {
		if err := os.WriteFile(filepath.Join(folder, "left-behind"), []byte("half an upload"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := os.MkdirAll(filepath.Join(folder, "a-folder", "inside"), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := c.store.Sweep(); err != nil {
		t.Fatal(err)
	}
	equal(t, []string{}, append(namesIn(t, c.store.IncomingDir), namesIn(t, c.store.TmpDir)...))
	c.assertCopyIsThePhone()
	equal(t, 0, c.sync().HoursWanted)
}

func TestTheCopyCarriesTheSchemaVersionAndRoomsIdentity(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 60)
	c.sync()
	conn, err := sqlite.OpenConn(c.store.ReplicaPath, sqlite.OpenReadOnly)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	version, _, _ := oneInt(conn, "PRAGMA user_version")
	equal(t, int64(41), version)
	vacuum, _, _ := oneInt(conn, "PRAGMA auto_vacuum")
	equal(t, int64(1), vacuum)
	identity, _, _ := oneText(conn, "SELECT identity_hash FROM room_master_table WHERE id = 42")
	equal(t, testIdentity, identity)
	indexes, _, _ := oneInt(conn, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_event_kind'")
	equal(t, int64(1), indexes)
}
