package tsbridge

import (
	"bufio"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
	"tailscale.com/types/key"
)

func TestPrepareStateDir(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "state")
	if err := prepareStateDir(dir); err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !fi.IsDir() {
		t.Fatalf("not a directory")
	}
	if got := fi.Mode().Perm(); got != 0o700 {
		t.Fatalf("mode %o, want 0700", got)
	}
	if err := prepareStateDir(""); err == nil {
		t.Fatal("empty stateDir should fail")
	}
}

func TestCaptureLoginURL(t *testing.T) {
	mu.Lock()
	prevGen, prevState, prevURL := generation, state, loginURL
	generation = 7
	state = "starting"
	loginURL = ""
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		generation, state, loginURL = prevGen, prevState, prevURL
		mu.Unlock()
	})

	captureLoginLine(7, "backend started")
	if LoginURL() != "" || State() != "starting" {
		t.Fatalf("state %s url %q", State(), LoginURL())
	}
	captureLoginLine(7, "see https://example.com/other")
	if LoginURL() != "" {
		t.Fatalf("non-login url captured: %s", LoginURL())
	}

	captureLoginLine(7, "To start this tsnet server, restart with TS_AUTHKEY set, or go to: https://login.tailscale.com/a/abc123")
	if got, want := LoginURL(), "https://login.tailscale.com/a/abc123"; got != want {
		t.Fatalf("login url %q, want %q", got, want)
	}
	if State() != "needs_login" {
		t.Fatalf("state %s", State())
	}

	captureLoginLine(7, "register at https://hs.example.com/register/nodekey:abcdef")
	if got, want := LoginURL(), "https://hs.example.com/register/nodekey:abcdef"; got != want {
		t.Fatalf("register url %q, want %q", got, want)
	}

	mu.Lock()
	state = "running"
	mu.Unlock()
	captureLoginLine(7, "again https://login.tailscale.com/a/abc123")
	if State() != "running" {
		t.Fatalf("state regressed to %s", State())
	}
	if LoginURL() != "" {
		t.Fatalf("LoginURL while running: %s", LoginURL())
	}

	// A late log from a previous generation must not clobber the current URL.
	mu.Lock()
	state = "starting"
	loginURL = ""
	mu.Unlock()
	captureLoginLine(6, "stale https://login.tailscale.com/a/stale")
	if LoginURL() != "" || State() != "starting" {
		t.Fatalf("stale generation applied: state %s url %q", State(), LoginURL())
	}
}

func TestProxyRejectsWhenStopped(t *testing.T) {
	saveProxyGlobals(t)
	mu.Lock()
	prevSrv, prevState := tsServer, state
	tsServer = nil
	state = "stopped"
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		tsServer, state = prevSrv, prevState
		mu.Unlock()
	})
	if _, err := Proxy("http://127.0.0.1:1", 0); err == nil {
		t.Fatal("expected error when stopped")
	}
	if _, err := Proxy("http://127.0.0.1:1", 12345); err == nil {
		t.Fatal("expected error when stopped, before binding the fixed port")
	}
}

func TestPeersWhenNotRunning(t *testing.T) {
	mu.Lock()
	prevSrv, prevState := tsServer, state
	tsServer = nil
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		tsServer, state = prevSrv, prevState
		mu.Unlock()
	})
	for _, st := range []string{"stopped", "starting", "needs_login", "error: boom"} {
		mu.Lock()
		state = st
		mu.Unlock()
		if got := Peers(); got != "[]" {
			t.Fatalf("state %s: Peers() = %s", st, got)
		}
	}
	// A live server pointer must not be touched unless State is running.
	mu.Lock()
	tsServer = &tsnet.Server{}
	state = "needs_login"
	mu.Unlock()
	if got := Peers(); got != "[]" {
		t.Fatalf("needs_login with a server: Peers() = %s", got)
	}
}

func TestPeersJSON(t *testing.T) {
	if got := peersJSON(nil); got != "[]" {
		t.Fatalf("nil: %s", got)
	}
	if got := peersJSON(&ipnstate.Status{}); got != "[]" {
		t.Fatalf("empty: %s", got)
	}
	if got := peersJSON(&ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{}}); got != "[]" {
		t.Fatalf("no peers: %s", got)
	}

	selfKey := mustNodeKey(t, 1)
	alpha := mustNodeKey(t, 2)
	nas := mustNodeKey(t, 3)
	zulu := mustNodeKey(t, 4)
	st := &ipnstate.Status{
		Self: &ipnstate.PeerStatus{
			PublicKey:    selfKey,
			HostName:     "phone",
			DNSName:      "phone.tail.ts.net.",
			TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
			Online:       true,
			OS:           "android",
		},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			zulu: {
				PublicKey: zulu,
				HostName:  "zulu",
				DNSName:   "zulu.tail.ts.net.",
				TailscaleIPs: []netip.Addr{
					{},
					netip.MustParseAddr("100.64.0.8"),
				},
				Online: false,
				OS:     "windows",
			},
			alpha: {
				PublicKey: alpha,
				HostName:  "alpha",
				DNSName:   "alpha.tail.ts.net.",
				TailscaleIPs: []netip.Addr{
					netip.MustParseAddr("100.64.0.2"),
					netip.MustParseAddr("fd7a:115c:a1e0::2"),
				},
				Online: true,
				OS:     "linux",
			},
			nas: {
				PublicKey: nas,
				HostName:  "nas",
				DNSName:   "nas.local",
				Online:    true,
				OS:        "freebsd",
			},
		},
	}
	const want = `[{"name":"alpha","dns":"alpha.tail.ts.net","ips":["100.64.0.2","fd7a:115c:a1e0::2"],"online":true,"os":"linux"},{"name":"nas","dns":"nas.local","ips":[],"online":true,"os":"freebsd"},{"name":"zulu","dns":"zulu.tail.ts.net","ips":["100.64.0.8"],"online":false,"os":"windows"}]`
	if got := peersJSON(st); got != want {
		t.Fatalf("peersJSON\n got %s\nwant %s", got, want)
	}
}

func mustNodeKey(t *testing.T, b byte) key.NodePublic {
	t.Helper()
	raw := make([]byte, 32)
	raw[31] = b
	var k key.NodePublic
	if err := k.UnmarshalText([]byte("nodekey:" + hex.EncodeToString(raw))); err != nil {
		t.Fatal(err)
	}
	return k
}

func TestLoopbackOnly(t *testing.T) {
	h := loopbackOnly(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	for _, tc := range []struct {
		addr string
		want int
	}{
		{"127.0.0.1:1234", http.StatusNoContent},
		{"10.0.0.8:50", http.StatusForbidden},
		{"127.0.0.2:50", http.StatusForbidden},
		{"[::1]:50", http.StatusForbidden},
	} {
		req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1/", nil)
		req.RemoteAddr = tc.addr
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, req)
		if rr.Code != tc.want {
			t.Errorf("%s: code %d, want %d", tc.addr, rr.Code, tc.want)
		}
	}
}

func TestProxyRewritesRequest(t *testing.T) {
	got := make(chan *http.Request, 1)
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cp := r.Clone(r.Context())
		cp.Body = nil
		got <- cp
		if r.Header.Get("Keep-Alive") != "" {
			http.Error(w, "hop-by-hop Keep-Alive was forwarded", http.StatusBadGateway)
			return
		}
		fmt.Fprintf(w, "ok %s", r.URL.RequestURI())
	}))
	defer backend.Close()

	dial := (&net.Dialer{Timeout: 2 * time.Second}).DialContext
	port, srv, err := startProxy(backend.URL, 0, dial)
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	conn, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	req := fmt.Sprintf(
		"GET /hello?x=1 HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nX-Demo: yes\r\nConnection: keep-alive\r\nKeep-Alive: timeout=5\r\n\r\n",
		port,
	)
	if _, err := io.WriteString(conn, req); err != nil {
		t.Fatal(err)
	}
	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d body %s", resp.StatusCode, body)
	}
	if string(body) != "ok /hello?x=1" {
		t.Fatalf("body %q", body)
	}

	select {
	case r := <-got:
		if r.Host != backend.Listener.Addr().String() {
			t.Errorf("Host %q, want %q", r.Host, backend.Listener.Addr().String())
		}
		if r.URL.Path != "/hello" || r.URL.RawQuery != "x=1" {
			t.Errorf("url %s", r.URL.RequestURI())
		}
		if r.Header.Get("X-Demo") != "yes" {
			t.Errorf("X-Demo %q", r.Header.Get("X-Demo"))
		}
		if r.Header.Get("Keep-Alive") != "" {
			t.Errorf("Keep-Alive forwarded: %q", r.Header.Get("Keep-Alive"))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("backend saw no request")
	}
}

func TestProxyStreamsWithoutBuffering(t *testing.T) {
	release := make(chan struct{})
	t.Cleanup(func() {
		select {
		case <-release:
		default:
			close(release)
		}
	})
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusOK)
		flusher := w.(http.Flusher)
		_, _ = w.Write([]byte("chunk1\n"))
		flusher.Flush()
		<-release
		_, _ = w.Write([]byte("chunk2\n"))
		flusher.Flush()
	}))
	defer backend.Close()

	port, srv, err := startProxy(backend.URL, 0, (&net.Dialer{}).DialContext)
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	resp, err := http.Get(fmt.Sprintf("http://127.0.0.1:%d/stream", port))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}

	type readResult struct {
		n   int
		err error
		buf []byte
	}
	ch := make(chan readResult, 1)
	go func() {
		buf := make([]byte, 32)
		n, err := resp.Body.Read(buf)
		ch <- readResult{n: n, err: err, buf: append([]byte(nil), buf[:n]...)}
	}()
	select {
	case r := <-ch:
		if !strings.Contains(string(r.buf), "chunk1") {
			t.Fatalf("first read %q err %v", r.buf, r.err)
		}
		if strings.Contains(string(r.buf), "chunk2") {
			t.Fatal("first read already contained chunk2; the body was buffered")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for the first chunk")
	}

	close(release)
	rest, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(rest), "chunk2") {
		t.Fatalf("rest %q", rest)
	}
}

func TestProxyWebSocketUpgrade(t *testing.T) {
	type seen struct {
		host      string
		path      string
		upgrade   string
		conn      string
		keepAlive string
		demo      string
	}
	saw := make(chan seen, 1)
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		saw <- seen{
			host:      r.Host,
			path:      r.URL.RequestURI(),
			upgrade:   r.Header.Get("Upgrade"),
			conn:      r.Header.Get("Connection"),
			keepAlive: r.Header.Get("Keep-Alive"),
			demo:      r.Header.Get("X-Demo"),
		}
		if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") ||
			!headerHasToken(r.Header.Get("Connection"), "Upgrade") {
			http.Error(w, "upgrade headers were not forwarded", http.StatusBadRequest)
			return
		}
		hj, ok := w.(http.Hijacker)
		if !ok {
			http.Error(w, "no hijack", http.StatusInternalServerError)
			return
		}
		conn, bufrw, err := hj.Hijack()
		if err != nil {
			return
		}
		defer conn.Close()
		if _, err := bufrw.WriteString("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"); err != nil {
			return
		}
		if err := bufrw.Flush(); err != nil {
			return
		}
		buf := make([]byte, 4)
		if _, err := io.ReadFull(bufrw, buf); err != nil {
			return
		}
		if string(buf) != "ping" {
			return
		}
		_, _ = bufrw.WriteString("pong")
		_ = bufrw.Flush()
	}))
	defer backend.Close()

	port, srv, err := startProxy(backend.URL, 0, (&net.Dialer{}).DialContext)
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	conn, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	raw := fmt.Sprintf(
		"GET /ws?room=1 HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nX-Demo: ws\r\nConnection: keep-alive, Upgrade\r\nKeep-Alive: timeout=5\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n",
		port,
	)
	if _, err := io.WriteString(conn, raw); err != nil {
		t.Fatal(err)
	}
	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		t.Fatalf("status %d body %s", resp.StatusCode, b)
	}
	defer resp.Body.Close()
	if !strings.EqualFold(resp.Header.Get("Upgrade"), "websocket") {
		t.Fatalf("response Upgrade %q", resp.Header.Get("Upgrade"))
	}
	if _, err := io.WriteString(conn, "ping"); err != nil {
		t.Fatal(err)
	}
	// Bytes after the 101 may already be buffered in br, not on conn.
	pong := make([]byte, 4)
	if _, err := io.ReadFull(br, pong); err != nil {
		t.Fatal(err)
	}
	if string(pong) != "pong" {
		t.Fatalf("payload %q", pong)
	}

	select {
	case s := <-saw:
		if s.host != backend.Listener.Addr().String() {
			t.Errorf("Host %q, want %q", s.host, backend.Listener.Addr().String())
		}
		if s.path != "/ws?room=1" {
			t.Errorf("path %q", s.path)
		}
		if !strings.EqualFold(s.upgrade, "websocket") {
			t.Errorf("Upgrade %q", s.upgrade)
		}
		if !headerHasToken(s.conn, "Upgrade") {
			t.Errorf("Connection %q", s.conn)
		}
		if s.keepAlive != "" {
			t.Errorf("Keep-Alive forwarded: %q", s.keepAlive)
		}
		if s.demo != "ws" {
			t.Errorf("X-Demo %q", s.demo)
		}
	default:
		t.Fatal("backend did not record the upgrade request")
	}
}

func TestProxyFixedPort(t *testing.T) {
	saveProxyGlobals(t)
	port := freeLoopbackPort(t)
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "fixed")
	}))
	defer backend.Close()

	got, err := installProxy(backend.URL, port, (&net.Dialer{}).DialContext)
	if err != nil {
		t.Fatal(err)
	}
	if got != port {
		t.Fatalf("port %d, want %d", got, port)
	}
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/x", port))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || string(body) != "fixed" {
		t.Fatalf("status %d body %q", resp.StatusCode, body)
	}
}

func TestProxyPortTaken(t *testing.T) {
	saveProxyGlobals(t)
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port

	got, err := installProxy("http://127.0.0.1:9", port, (&net.Dialer{}).DialContext)
	if err == nil {
		t.Fatalf("expected port-taken error, got port %d", got)
	}
	if got != 0 {
		t.Fatalf("error returned port %d", got)
	}
	srv, bound := currentProxy()
	if srv != nil || bound != 0 {
		t.Fatal("failed listen stored a proxy")
	}
}

func TestProxySwapKeepsListener(t *testing.T) {
	saveProxyGlobals(t)
	port := freeLoopbackPort(t)
	dial := (&net.Dialer{}).DialContext

	var once sync.Once
	entered := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseFn := func() { releaseOnce.Do(func() { close(release) }) }
	t.Cleanup(releaseFn)

	a := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/hold" {
			once.Do(func() { close(entered) })
			<-release
			io.WriteString(w, "from-a-hold")
			return
		}
		io.WriteString(w, "from-a")
	}))
	defer a.Close()
	b := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "from-b")
	}))
	defer b.Close()
	c := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "from-c")
	}))
	defer c.Close()

	got, err := installProxy(a.URL, port, dial)
	if err != nil {
		t.Fatal(err)
	}
	if got != port {
		t.Fatalf("port %d, want %d", got, port)
	}
	srv, bound := currentProxy()
	if srv == nil || bound != port {
		t.Fatalf("listener not stored on %d", port)
	}

	if _, err := installProxy(b.URL, port+1, dial); err == nil {
		t.Fatal("a different fixed port should fail while the listener is up")
	}
	if _, err := installProxy("ftp://example.com", port, dial); err == nil {
		t.Fatal("bad target should fail without touching the listener")
	}
	if cur, p := currentProxy(); cur != srv || p != port {
		t.Fatal("rejected call replaced the listener")
	}
	if body := proxyGet(t, port, "/still"); body != "from-a" {
		t.Fatalf("body %q after rejected swap", body)
	}

	type result struct {
		body string
		err  error
	}
	ch := make(chan result, 1)
	holdClient := &http.Client{Timeout: 15 * time.Second}
	go func() {
		resp, err := holdClient.Get(fmt.Sprintf("http://127.0.0.1:%d/hold", port))
		if err != nil {
			ch <- result{err: err}
			return
		}
		defer resp.Body.Close()
		b, err := io.ReadAll(resp.Body)
		ch <- result{body: string(b), err: err}
	}()
	select {
	case <-entered:
	case r := <-ch:
		t.Fatalf("hold request ended early: %q %v", r.body, r.err)
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for the held request")
	}

	got, err = installProxy(b.URL, port, dial)
	if err != nil {
		t.Fatal(err)
	}
	if got != port {
		t.Fatalf("swap returned port %d, want %d", got, port)
	}
	if cur, p := currentProxy(); cur != srv || p != port {
		t.Fatal("swap dropped the listener")
	}
	if body := proxyGet(t, port, "/now"); body != "from-b" {
		t.Fatalf("after swap, body %q", body)
	}

	// port 0 on a live proxy keeps the listener and still updates the target.
	got, err = installProxy(c.URL, 0, dial)
	if err != nil {
		t.Fatal(err)
	}
	if got != port {
		t.Fatalf("port 0 swap returned %d, want %d", got, port)
	}
	if cur, p := currentProxy(); cur != srv || p != port {
		t.Fatal("port 0 swap dropped the listener")
	}
	if body := proxyGet(t, port, "/c"); body != "from-c" {
		t.Fatalf("after port 0 swap, body %q", body)
	}

	releaseFn()
	select {
	case r := <-ch:
		if r.err != nil {
			t.Fatal(r.err)
		}
		if r.body != "from-a-hold" {
			t.Fatalf("held request body %q", r.body)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("held request did not finish after the target swap")
	}
}

func TestRejectsBadTarget(t *testing.T) {
	dial := (&net.Dialer{}).DialContext
	if _, _, err := startProxy("ftp://example.com", 0, dial); err == nil {
		t.Fatal("ftp should fail")
	}
	if _, _, err := startProxy("://bad", 0, dial); err == nil {
		t.Fatal("bad url should fail")
	}
	if _, _, err := startProxy("http://", 0, dial); err == nil {
		t.Fatal("missing host should fail")
	}
	if _, _, err := startProxy("http://127.0.0.1:1", -1, dial); err == nil {
		t.Fatal("negative port should fail")
	}
	if _, _, err := startProxy("http://127.0.0.1:1", 65536, dial); err == nil {
		t.Fatal("port above 65535 should fail")
	}
}

func saveProxyGlobals(t *testing.T) {
	t.Helper()
	mu.Lock()
	prevSrv, prevHandler, prevPort := proxySrv, proxyHandler, proxyPort
	proxySrv = nil
	proxyHandler = nil
	proxyPort = 0
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		cur := proxySrv
		proxySrv, proxyHandler, proxyPort = prevSrv, prevHandler, prevPort
		mu.Unlock()
		if cur != nil && cur != prevSrv {
			cur.Close()
		}
	})
}

func currentProxy() (*http.Server, int) {
	mu.Lock()
	defer mu.Unlock()
	return proxySrv, proxyPort
}

func freeLoopbackPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	if err := ln.Close(); err != nil {
		t.Fatal(err)
	}
	return port
}

func proxyGet(t *testing.T, port int, path string) string {
	t.Helper()
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d%s", port, path))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d body %q", resp.StatusCode, body)
	}
	return string(body)
}

func headerHasToken(value, token string) bool {
	for _, part := range strings.Split(value, ",") {
		if strings.EqualFold(strings.TrimSpace(part), token) {
			return true
		}
	}
	return false
}
