package backup

// The server's folder, and the three things a request can do to it: set the schema, plan, take a delta.

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"syscall"
	"time"
)

// Limits. They are variables so the tests can move them.
var (
	maxUnpacked int64 = 512 << 20 // a delta file, unpacked
	diskMargin  int64 = 256 << 20 // kept free beyond what a merge needs
)

const lowDisk = 10_000_000_000 // `status` warns below this

// gunzipTo unpacks src into dest and returns the size, stopping as soon as it passes limit.
func gunzipTo(src, dest string, limit int64) (int64, error) {
	packed, err := os.Open(src)
	if err != nil {
		return 0, err
	}
	defer packed.Close()
	unpacker, err := gzip.NewReader(packed)
	if err != nil {
		return 0, refuse(422, "the upload is not a gzip file")
	}
	unpacker.Multistream(false)
	out, err := os.Create(dest)
	if err != nil {
		return 0, err
	}
	defer out.Close()
	n, err := io.Copy(out, io.LimitReader(unpacker, limit+1))
	if n > limit {
		return 0, refuse(413, "the upload is too large unpacked")
	}
	if err != nil {
		if errors.Is(err, io.ErrUnexpectedEOF) {
			return 0, refuse(422, "the upload is not a whole gzip file")
		}
		var pathErr *os.PathError
		if errors.As(err, &pathErr) {
			return 0, err // the disk, not the upload
		}
		return 0, refuse(422, "the upload is not a gzip file")
	}
	return n, out.Close()
}

func sha256Of(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	digest := sha256.New()
	if _, err := io.Copy(digest, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func freeBytes(dir string) (int64, error) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(dir, &stat); err != nil {
		return 0, err
	}
	return int64(stat.Bavail) * int64(stat.Bsize), nil
}

// Store is one folder: the copy, the ledger, the log of uploads, and uploads still arriving. One writer at a time.
type Store struct {
	Dir, ReplicaPath, LedgerPath, LogDir, IncomingDir, TmpDir string

	mu    sync.Mutex
	shape *Shape
}

func NewStore(dataDir string) (*Store, error) {
	dir, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	s := &Store{
		Dir:         dir,
		ReplicaPath: filepath.Join(dir, "replica.sqlite"),
		LedgerPath:  filepath.Join(dir, "ledger.sqlite"),
		LogDir:      filepath.Join(dir, "log"),
		IncomingDir: filepath.Join(dir, "incoming"),
		TmpDir:      filepath.Join(dir, "tmp"),
	}
	for _, folder := range []string{s.Dir, s.LogDir, s.IncomingDir, s.TmpDir} {
		if err := os.MkdirAll(folder, 0o700); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// Sweep clears what an interrupted upload or export left behind. Called when the server starts.
func (s *Store) Sweep() error {
	for _, folder := range []string{s.IncomingDir, s.TmpDir} {
		entries, err := os.ReadDir(folder)
		if err != nil {
			return err
		}
		for _, entry := range entries {
			if err := os.RemoveAll(filepath.Join(folder, entry.Name())); err != nil {
				return err
			}
		}
	}
	if exists(s.ReplicaPath) {
		// A write cut short leaves a journal that only a connection allowed to write can undo. Opening
		// one here means the read-only commands never meet it.
		conn, err := connect(s.ReplicaPath, "rw")
		if err != nil {
			return err
		}
		defer conn.Close()
		_, _, err = oneInt(conn, "SELECT count(*) FROM sqlite_master")
		return err
	}
	return nil
}

func (s *Store) known() (Shape, error) {
	if s.shape == nil {
		conn, err := connect(s.ReplicaPath, "ro")
		if err != nil {
			return Shape{}, err
		}
		defer conn.Close()
		found, err := shapeOf(conn, "main")
		if err != nil {
			return Shape{}, err
		}
		s.shape = &found
	}
	return *s.shape, nil
}

func (s *Store) state(ledger *Ledger, userVersion *int64, identityHash string) string {
	storedIdentity, ok := ledger.get("identity_hash")
	if !ok || !exists(s.ReplicaPath) {
		return "missing"
	}
	storedVersion, _ := ledger.get("user_version")
	if userVersion != nil && storedVersion == strconv.FormatInt(*userVersion, 10) && storedIdentity == identityHash {
		return "ok"
	}
	return "mismatch"
}

// schemaRequest is the body of PUT /v1/schema. The fields are loose so each can be refused with its own reason.
type schemaRequest struct {
	Format       any   `json:"format"`
	UserVersion  any   `json:"userVersion"`
	IdentityHash any   `json:"identityHash"`
	PageSize     any   `json:"pageSize"`
	AutoVacuum   any   `json:"autoVacuum"`
	Statements   []any `json:"statements"`
}

// PutSchema makes the empty copy. raw is the request as it arrived, kept in the log.
func (s *Store) PutSchema(body schemaRequest, raw []byte) (map[string]any, error) {
	format, formatOK := wholeNumber(body.Format)
	userVersion, versionOK := wholeNumber(body.UserVersion)
	if !formatOK || format != Format || !versionOK {
		return nil, refuse(400, "unknown format or schema version")
	}
	identityHash, _ := body.IdentityHash.(string)
	if identityHash == "" {
		return nil, refuse(400, "the schema's identity is missing")
	}
	pageSize, autoVacuum := int64(4096), int64(1)
	if body.PageSize != nil {
		n, ok := wholeNumber(body.PageSize)
		if !ok {
			return nil, refuse(422, "unknown page size or vacuum mode")
		}
		pageSize = n
	}
	if body.AutoVacuum != nil {
		n, ok := wholeNumber(body.AutoVacuum)
		if !ok {
			return nil, refuse(422, "unknown page size or vacuum mode")
		}
		autoVacuum = n
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	ledger, err := openLedger(s.LedgerPath)
	if err != nil {
		return nil, err
	}
	defer ledger.Close()
	if exists(s.ReplicaPath) {
		return nil, refuse(409, "a copy already exists")
	}
	tables, err := createReplica(s.ReplicaPath, userVersion, body.Statements, pageSize, autoVacuum)
	if err != nil {
		return nil, err
	}
	s.shape = nil
	now := time.Now()
	sum := sha256.Sum256(raw)
	sha := hex.EncodeToString(sum[:])
	name := fmt.Sprintf("%013d-%s.schema.json.gz", now.UnixMilli(), sha)
	var packed bytes.Buffer
	zw := gzip.NewWriter(&packed)
	zw.Write(raw)
	zw.Close()
	if err := os.WriteFile(filepath.Join(s.LogDir, name), packed.Bytes(), 0o600); err != nil {
		return nil, err
	}
	_, err = ledger.record(uploadRecord{
		ReceivedAt: now.Unix(), Kind: "schema", SHA256: sha, Size: int64(len(raw)), Status: "merged", LogFile: name,
		Meta: map[string]string{
			"format":        strconv.Itoa(Format),
			"created_at":    strconv.FormatInt(now.Unix(), 10),
			"user_version":  strconv.FormatInt(userVersion, 10),
			"identity_hash": identityHash,
		},
	})
	if err != nil {
		return nil, err
	}
	return map[string]any{"schema": "created", "tables": tables}, nil
}

// planRequest is the body of POST /v1/plan: the phone's checksums by table, strap and hour.
type planRequest struct {
	Format       int                                     `json:"format"`
	UserVersion  *int64                                  `json:"userVersion"`
	IdentityHash string                                  `json:"identityHash"`
	Prints       map[string]map[string]map[string]string `json:"prints"`
}

// Plan says which of the hours the phone lists the server wants: those it never merged, or merged with
// another checksum.
func (s *Store) Plan(body planRequest) (map[string]any, error) {
	if body.Format != Format || body.Prints == nil {
		return nil, refuse(400, "unknown format, or no checksums")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	ledger, err := openLedger(s.LedgerPath)
	if err != nil {
		return nil, err
	}
	defer ledger.Close()
	want := map[string]map[string][]int64{}
	if state := s.state(ledger, body.UserVersion, body.IdentityHash); state != "ok" {
		return map[string]any{"schema": state, "want": want}, nil
	}
	known, err := s.known()
	if err != nil {
		return nil, err
	}
	for table, devices := range body.Prints {
		if cols, ok := known.Tables[table]; !ok || !isSample(cols) {
			return nil, refuse(400, "checksums were sent for a table that is not a sample table")
		}
		held, err := ledger.prints(table)
		if err != nil {
			return nil, err
		}
		for device, hours := range devices {
			for text, print := range hours {
				hour, err := strconv.ParseInt(text, 10, 64)
				if err != nil {
					return nil, refuse(400, "the checksums are malformed")
				}
				if held[Bucket{device, hour}] != print {
					if want[table] == nil {
						want[table] = map[string][]int64{}
					}
					want[table][device] = append(want[table][device], hour)
				}
			}
		}
	}
	for _, devices := range want {
		for _, hours := range devices {
			sort.Slice(hours, func(i, j int) bool { return hours[i] < hours[j] })
		}
	}
	return map[string]any{"schema": "ok", "want": want}, nil
}

// TakeDelta checks and merges the upload at gzPath, which this call takes over: it ends up in the log
// or deleted.
func (s *Store) TakeDelta(gzPath, sha string) (map[string]any, error) {
	unpacked := gzPath + ".sqlite"
	s.mu.Lock()
	defer s.mu.Unlock()
	defer os.Remove(unpacked)
	defer os.Remove(gzPath) // gone already when it went into the log
	ledger, err := openLedger(s.LedgerPath)
	if err != nil {
		return nil, err
	}
	defer ledger.Close()

	seen, err := ledger.upload(sha)
	if err != nil {
		return nil, err
	}
	if seen != nil && seen.Status == "merged" {
		return map[string]any{"upload": seen.ID, "rows": seen.Rows, "again": true}, nil
	}
	storedVersion, known := ledger.get("user_version")
	identityHash, _ := ledger.get("identity_hash")
	if !known || !exists(s.ReplicaPath) {
		return nil, refuse(409, "there is no copy yet: send the schema first")
	}
	userVersion, _ := strconv.ParseInt(storedVersion, 10, 64)
	info, err := os.Stat(gzPath)
	if err != nil {
		return nil, err
	}
	now := time.Now()

	merged, err := func() (*Merged, error) {
		needed, err := gunzipTo(gzPath, unpacked, maxUnpacked)
		if err != nil {
			return nil, err
		}
		free, err := freeBytes(s.Dir)
		if err != nil {
			return nil, err
		}
		if free < 2*needed+diskMargin {
			return nil, refuse(507, "the server is short of disk")
		}
		conn, err := connect(s.ReplicaPath, "rw")
		if err != nil {
			return nil, err
		}
		defer conn.Close()
		return merge(conn, unpacked, userVersion, identityHash)
	}()
	if err != nil {
		var refused *Refused
		if errors.As(err, &refused) {
			if _, recordErr := ledger.record(uploadRecord{
				ReceivedAt: now.Unix(), Kind: "delta", SHA256: sha, Size: info.Size(), Status: "refused", Error: refused.Message,
			}); recordErr != nil {
				return nil, recordErr
			}
		}
		return nil, err
	}

	// The copy is written. If the server dies before the ledger is, the phone's next plan asks for the
	// same hours and merging them again changes nothing.
	name := fmt.Sprintf("%013d-%s.sqlite.gz", now.UnixMilli(), sha)
	if err := os.Rename(gzPath, filepath.Join(s.LogDir, name)); err != nil {
		return nil, err
	}
	kept := map[string]string{}
	for _, key := range []string{"app_build", "app_version", "settings_json"} {
		if value, ok := merged.Meta[key]; ok {
			kept[key] = value
		}
	}
	var rows int64
	for _, n := range merged.Rows {
		rows += n
	}
	id, err := ledger.record(uploadRecord{
		ReceivedAt: now.Unix(), Kind: "delta", SHA256: sha, Size: info.Size(), Rows: rows, AppBuild: merged.Meta["app_build"],
		Status: "merged", LogFile: name, Buckets: merged.Buckets, Meta: kept,
	})
	if err != nil {
		return nil, err
	}
	return map[string]any{"upload": id, "rows": rows, "hours": len(merged.Buckets), "again": false}, nil
}
