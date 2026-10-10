package backup

// The HTTP side: four routes, three of them behind the token, and none that returns data.
//
// It sits behind the reverse proxy, which does the HTTPS, and hears only from it.

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
	"time"
)

const (
	maxDelta        = 64 << 20 // a delta as sent, compressed
	maxJSON         = 8 << 20  // a plan or a schema as sent
	maxJSONUnpacked = 32 << 20
)

var sha256Pattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// TokenHash is what the server keeps of a token: its SHA-256, in hex.
func TokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// NewToken makes a token: 32 random bytes, written so that they can be pasted.
func NewToken() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

// wholeNumber reads a JSON number that must be a whole one. A boolean or a string is not a number.
func wholeNumber(v any) (int64, bool) {
	switch n := v.(type) {
	case json.Number:
		i, err := n.Int64()
		return i, err == nil
	case float64:
		return int64(n), n == float64(int64(n))
	case int:
		return int64(n), true
	case int64:
		return n, true
	}
	return 0, false
}

type handler struct {
	store       *Store
	tokenSHA256 string
	log         func(string)
}

// NewHandler serves the four routes. log gets one line per request: counts and sizes only, never a row,
// a checksum or the token.
func NewHandler(store *Store, tokenSHA256 string, log func(string)) (http.Handler, error) {
	if !sha256Pattern.MatchString(tokenSHA256) {
		return nil, errors.New("LHOOP_TOKEN_SHA256 is not set to a token's SHA-256. Make one with: lhoop-backup token new")
	}
	return &handler{store, tokenSHA256, log}, nil
}

func (h *handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	started := time.Now()
	status := h.serve(w, r)
	length := r.Header.Get("Content-Length")
	if length == "" {
		length = "-"
	}
	h.log(fmt.Sprintf("%s %s %s %d in=%s %dms", time.Now().UTC().Format("2006-01-02T15:04:05Z"),
		r.Method, r.URL.Path, status, length, time.Since(started).Milliseconds()))
}

func (h *handler) serve(w http.ResponseWriter, r *http.Request) int {
	if r.Method == http.MethodGet && r.URL.Path == "/healthz" {
		return send(w, http.StatusOK, "text/plain", []byte("ok\n"))
	}
	if !h.authorised(r) {
		return reply(w, http.StatusUnauthorized, map[string]any{"error": "unauthorised"})
	}
	var answer map[string]any
	var err error
	okStatus := http.StatusOK
	switch r.Method + " " + r.URL.Path {
	case "PUT /v1/schema":
		okStatus = http.StatusCreated
		var body schemaRequest
		var raw []byte
		if raw, err = jsonBody(r, &body); err == nil {
			answer, err = h.store.PutSchema(body, raw)
		}
	case "POST /v1/plan":
		var body planRequest
		if _, err = jsonBody(r, &body); err == nil {
			answer, err = h.store.Plan(body)
		}
	case "POST /v1/delta":
		answer, err = h.delta(r)
	default:
		return reply(w, http.StatusNotFound, map[string]any{"error": "no such route"})
	}
	if err != nil {
		var refused *Refused
		if errors.As(err, &refused) {
			return reply(w, refused.Status, map[string]any{"error": refused.Message})
		}
		fmt.Fprintf(os.Stderr, "lhoop-backup: %s %s failed: %v\n", r.Method, r.URL.Path, err)
		return reply(w, http.StatusInternalServerError, map[string]any{"error": "the server failed"})
	}
	return reply(w, okStatus, answer)
}

func (h *handler) authorised(r *http.Request) bool {
	token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !found {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(TokenHash(strings.TrimSpace(token))), []byte(h.tokenSHA256)) == 1
}

// length is how long the request says its body is, refused when it does not say or says too much.
func length(r *http.Request, limit int64) (int64, error) {
	if r.Header.Get("Content-Length") == "" || r.ContentLength < 0 {
		return 0, refuse(http.StatusLengthRequired, "the request must say how long it is")
	}
	if r.ContentLength > limit {
		return 0, refuse(http.StatusRequestEntityTooLarge, "the request is too large")
	}
	return r.ContentLength, nil
}

// jsonBody reads a JSON object into into, and returns it as it was sent.
func jsonBody(r *http.Request, into any) ([]byte, error) {
	n, err := length(r, maxJSON)
	if err != nil {
		return nil, err
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, n))
	if err != nil {
		return nil, err
	}
	if strings.EqualFold(r.Header.Get("Content-Encoding"), "gzip") {
		unpacker, err := gzip.NewReader(bytes.NewReader(raw))
		if err != nil {
			return nil, refuse(http.StatusBadRequest, "the body is not gzip")
		}
		raw, err = io.ReadAll(io.LimitReader(unpacker, maxJSONUnpacked+1))
		if err != nil {
			return nil, refuse(http.StatusBadRequest, "the body is not gzip")
		}
		if len(raw) > maxJSONUnpacked {
			return nil, refuse(http.StatusRequestEntityTooLarge, "the request is too large unpacked")
		}
	}
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || trimmed[0] != '{' {
		return nil, refuse(http.StatusBadRequest, "the body is not a JSON object")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err := decoder.Decode(into); err != nil {
		return nil, refuse(http.StatusBadRequest, "the body is not the JSON this route takes")
	}
	return raw, nil
}

func (h *handler) delta(r *http.Request) (map[string]any, error) {
	n, err := length(r, maxDelta)
	if err != nil {
		return nil, err
	}
	claimed := strings.ToLower(r.Header.Get("X-Lhoop-Sha256"))
	if !sha256Pattern.MatchString(claimed) {
		return nil, refuse(http.StatusBadRequest, "the upload must carry its SHA-256")
	}
	name := make([]byte, 8)
	if _, err := rand.Read(name); err != nil {
		return nil, err
	}
	path := filepath.Join(h.store.IncomingDir, hex.EncodeToString(name)+".gz")
	out, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, err
	}
	digest := sha256.New()
	got, err := io.Copy(io.MultiWriter(out, digest), io.LimitReader(r.Body, n))
	if closeErr := out.Close(); err == nil {
		err = closeErr
	}
	switch {
	case err != nil:
	case got != n:
		err = errors.New("the upload stopped part-way")
	case hex.EncodeToString(digest.Sum(nil)) != claimed:
		err = refuse(http.StatusBadRequest, "the upload does not match its SHA-256")
	}
	if err != nil {
		os.Remove(path)
		return nil, err
	}
	return h.store.TakeDelta(path, claimed)
}

func reply(w http.ResponseWriter, status int, body map[string]any) int {
	payload, _ := json.Marshal(body)
	return send(w, status, "application/json", append(payload, '\n'))
}

func send(w http.ResponseWriter, status int, kind string, payload []byte) int {
	w.Header().Set("Content-Type", kind)
	w.Header().Set("Content-Length", fmt.Sprint(len(payload)))
	w.Header().Set("Cache-Control", "no-store")
	if status >= 400 {
		w.Header().Set("Connection", "close") // the rest of an unread body must not be taken for a request
	}
	w.WriteHeader(status)
	w.Write(payload)
	return status
}

// Serve runs the server until it is told to stop.
func Serve(dataDir, tokenSHA256, bind string, port int, stdout io.Writer) error {
	store, err := NewStore(dataDir)
	if err != nil {
		return err
	}
	if err := store.Sweep(); err != nil {
		return err
	}
	h, err := NewHandler(store, tokenSHA256, func(line string) { fmt.Fprintln(stdout, line) })
	if err != nil {
		return err
	}
	server := &http.Server{
		Addr:              fmt.Sprintf("%s:%d", bind, port),
		Handler:           h,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       10 * time.Minute, // a whole upload, over a slow link
		WriteTimeout:      20 * time.Minute, // the answer to a delta comes only once it is merged
		IdleTimeout:       time.Minute,
		MaxHeaderBytes:    16 << 10,
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, os.Interrupt)
	go func() {
		<-stop
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		server.Shutdown(ctx)
	}()
	fmt.Fprintf(stdout, "lhoop-backup listening on %s, data in %s\n", server.Addr, store.Dir)
	if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
