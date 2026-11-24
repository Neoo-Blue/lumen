// Package tsbridge embeds a Tailscale userspace node (tsnet) and exposes a
// localhost HTTP reverse proxy so an Android WebView can reach a Lumen server
// on the tailnet. No VpnService and no TUN device: traffic is dialed through
// tsnet. Exported functions use only gomobile-supported types.
package tsbridge

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"

	// Registers a netmon fallback so tsnet can start on Android without
	// NETLINK_ROUTE. Without it, startup dies with "netlinkrib: permission denied".
	// Present since tailscale commit 678b3be. Build with ts_omit_androidbin to drop it.
	_ "tailscale.com/feature/androidbin"
)

func init() {
	// Set before any tsnet start. envknob.NoLogsNoSupport reads this at use
	// time (not at package init), and Start sets it again before Server.Start.
	_ = os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
}

// dialFunc matches net.Dialer.DialContext and tsnet.Server.Dial.
type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

var (
	startGate sync.Mutex
	mu        sync.Mutex

	tsServer     *tsnet.Server
	upCancel     context.CancelFunc
	proxySrv     *http.Server
	proxyHandler *swappingHandler
	proxyPort    int
	generation   int

	state     = "stopped"
	loginURL  string
	tailnetIP string

	// bridgeSecret authenticates loopback clients when it is long enough.
	// Stop does not clear it: the secret belongs to the install, not to one
	// tailnet session.
	bridgeSecret   string
	bridgeWarnOnce sync.Once
)

// bridgeCookieName is the cookie a trusted WebView must send once SetSecret
// has armed the proxy. It is stripped again before the request is forwarded.
const (
	bridgeCookieName   = "lumen_bridge"
	bridgeSecretMinLen = 32
)

var loginURLRe = regexp.MustCompile(`https://[^\s"'<>]+`)

// Start brings up a tsnet node in the background. stateDir is created with
// mode 0700. An empty authKey selects interactive login. An empty controlURL
// selects the default Tailscale coordination server (set it for Headscale).
// The call returns after the node has been started, without waiting for the
// tailnet to reach the running state. Read State, LoginURL, and TailnetIP
// while it comes up.
func Start(stateDir, hostname, authKey, controlURL string) error {
	if err := prepareStateDir(stateDir); err != nil {
		return err
	}
	// No log upload to Tailscale. SetNoLogsNoSupport also refreshes envknob's
	// cache in case something registered the variable earlier.
	if err := os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true"); err != nil {
		return err
	}
	envknob.SetNoLogsNoSupport()

	startGate.Lock()
	defer startGate.Unlock()

	mu.Lock()
	if tsServer != nil {
		mu.Unlock()
		return errors.New("tsbridge: already started")
	}
	generation++
	gen := generation
	state = "starting"
	loginURL = ""
	tailnetIP = ""
	mu.Unlock()
	log.Printf("tsbridge: starting")

	srv := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		UserLogf: func(format string, args ...any) {
			captureLoginLine(gen, fmt.Sprintf(format, args...))
		},
	}

	// Start is synchronous and does not wait for login. Up waits for the
	// running state, so it runs in the background. Close must not overlap
	// Start; startGate serializes them.
	if err := srv.Start(); err != nil {
		mu.Lock()
		if generation == gen {
			state = "error: " + err.Error()
		}
		mu.Unlock()
		log.Printf("tsbridge: start failed")
		_ = srv.Close()
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	mu.Lock()
	if generation != gen {
		mu.Unlock()
		cancel()
		_ = srv.Close()
		return errors.New("tsbridge: stopped during start")
	}
	tsServer = srv
	upCancel = cancel
	mu.Unlock()

	go runUp(gen, srv, ctx)
	return nil
}

func runUp(gen int, srv *tsnet.Server, ctx context.Context) {
	st, err := srv.Up(ctx)
	mu.Lock()
	defer mu.Unlock()
	if generation != gen {
		return
	}
	if err != nil {
		if ctx.Err() != nil {
			state = "stopped"
			log.Printf("tsbridge: stopped")
			return
		}
		state = "error: " + err.Error()
		log.Printf("tsbridge: error")
		return
	}
	tailnetIP = firstTailnetIP(st)
	loginURL = ""
	state = "running"
	log.Printf("tsbridge: running")
}

func firstTailnetIP(st *ipnstate.Status) string {
	if st == nil {
		return ""
	}
	var v6 string
	for _, ip := range st.TailscaleIPs {
		if !ip.IsValid() {
			continue
		}
		if ip.Is4() {
			return ip.String()
		}
		if v6 == "" && ip.Is6() {
			v6 = ip.String()
		}
	}
	return v6
}

// LoginURL returns the interactive login URL while State is needs_login,
// otherwise "".
func LoginURL() string {
	mu.Lock()
	defer mu.Unlock()
	if state != "needs_login" {
		return ""
	}
	return loginURL
}

// State returns one of "starting", "needs_login", "running", "stopped",
// or "error: <msg>".
func State() string {
	mu.Lock()
	defer mu.Unlock()
	return state
}

// TailnetIP returns the node's IPv4 address once the tailnet is up, or the
// IPv6 address when there is no IPv4 address. It is empty until then.
func TailnetIP() string {
	mu.Lock()
	defer mu.Unlock()
	return tailnetIP
}

// peerWire is one tailnet device, in the JSON shape Peers returns.
type peerWire struct {
	Name   string   `json:"name"`
	DNS    string   `json:"dns"`
	IPs    []string `json:"ips"`
	Online bool     `json:"online"`
	OS     string   `json:"os"`
}

// Peers returns a JSON array of the tailnet's other devices, taken from the
// running tsnet server's LocalClient status. Each element is
// {"name": HostName, "dns": DNSName without a trailing dot, "ips": [Tailscale IPs], "online": bool, "os": OS}.
// Devices are ordered by DNS name. The result is "[]" when the node is not running.
func Peers() string {
	mu.Lock()
	srv := tsServer
	st := state
	gen := generation
	mu.Unlock()
	if srv == nil || st != "running" {
		return "[]"
	}
	lc, err := srv.LocalClient()
	if err != nil {
		log.Printf("tsbridge: peers: %v", err)
		return "[]"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := lc.Status(ctx)
	if err != nil {
		log.Printf("tsbridge: peers: %v", err)
		return "[]"
	}
	mu.Lock()
	still := generation == gen && state == "running" && tsServer == srv
	mu.Unlock()
	if !still {
		return "[]"
	}
	return peersJSON(status)
}

// peersJSON converts a LocalAPI status into the Peers JSON array.
// Self is omitted: Peer holds the other devices. A nil or empty status is "[]".
func peersJSON(st *ipnstate.Status) string {
	if st == nil || len(st.Peer) == 0 {
		return "[]"
	}
	list := make([]*ipnstate.PeerStatus, 0, len(st.Peer))
	for _, ps := range st.Peer {
		if ps != nil {
			list = append(list, ps)
		}
	}
	if len(list) == 0 {
		return "[]"
	}
	ipnstate.SortPeers(list)
	out := make([]peerWire, 0, len(list))
	for _, ps := range list {
		ips := make([]string, 0, len(ps.TailscaleIPs))
		for _, ip := range ps.TailscaleIPs {
			if !ip.IsValid() {
				continue
			}
			ips = append(ips, ip.String())
		}
		out = append(out, peerWire{
			Name:   ps.HostName,
			DNS:    strings.TrimSuffix(ps.DNSName, "."),
			IPs:    ips,
			Online: ps.Online,
			OS:     ps.OS,
		})
	}
	b, err := json.Marshal(out)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// SetSecret installs the per-install proxy secret. A secret of at least 32
// characters makes the loopback proxy reject every request, including
// WebSocket upgrades, unless the request carries a cookie named lumen_bridge
// whose value equals secret. The comparison is constant-time. The cookie is
// removed before the request is forwarded, so the Lumen server never sees it.
// The value has to be a valid cookie token: no spaces or semicolons.
//
// An empty string, or a secret shorter than 32 characters, turns the check
// off so an older app build keeps working. Stop does not clear the secret.
func SetSecret(secret string) {
	if secret != "" && len(secret) < bridgeSecretMinLen {
		log.Printf("tsbridge: proxy secret is too short; the proxy is not authenticating clients")
	}
	mu.Lock()
	bridgeSecret = secret
	mu.Unlock()
}

func currentBridgeSecret() string {
	mu.Lock()
	defer mu.Unlock()
	return bridgeSecret
}

// Proxy listens on 127.0.0.1 and reverse-proxies every request to target,
// dialing through the tsnet node. target is an absolute URL such as
// "https://lumen.tail1234.ts.net" or "http://100.x.y.z:8080".
//
// When SetSecret has installed a secret of at least 32 characters, every
// request must include a lumen_bridge cookie equal to that secret. The cookie
// is stripped before the request is forwarded. With no such secret, any
// loopback client is accepted so older app builds keep working.
//
// port 0 asks the kernel for an ephemeral port. Any other port listens on
// exactly 127.0.0.1:port, and an error is returned if that port is already
// taken. A later call while the proxy is up keeps that listener and only
// changes where requests are forwarded, so a fixed port (and the WebView
// origin that depends on it) stays the same across launches. The returned
// port is the one the WebView should use.
func Proxy(target string, port int) (int, error) {
	startGate.Lock()
	defer startGate.Unlock()

	mu.Lock()
	srv := tsServer
	st := state
	mu.Unlock()
	if srv == nil || st == "stopped" || strings.HasPrefix(st, "error:") {
		return 0, errors.New("tsbridge: tailscale is not running")
	}
	return installProxy(target, port, srv.Dial)
}

// Stop shuts down the proxy and the tsnet node. It is safe to call more than once.
func Stop() {
	startGate.Lock()
	defer startGate.Unlock()

	mu.Lock()
	generation++
	srv := tsServer
	cancel := upCancel
	ps := proxySrv
	tsServer = nil
	upCancel = nil
	proxySrv = nil
	proxyHandler = nil
	proxyPort = 0
	state = "stopped"
	loginURL = ""
	tailnetIP = ""
	mu.Unlock()
	log.Printf("tsbridge: stopped")

	if cancel != nil {
		cancel()
	}
	if ps != nil {
		_ = ps.Close()
	}
	if srv != nil {
		_ = srv.Close()
	}
}

func prepareStateDir(stateDir string) error {
	if stateDir == "" {
		return errors.New("tsbridge: stateDir is empty")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return err
	}
	return os.Chmod(stateDir, 0o700)
}

// captureLoginLine records an interactive login URL from a tsnet UserLogf line.
// Tailscale prints https://login.tailscale.com/a/<id>. Headscale prints a
// /register/ URL. The line must contain both "https://" and one of those paths.
func captureLoginLine(gen int, msg string) {
	if !strings.Contains(msg, "https://") {
		return
	}
	if !strings.Contains(msg, "/a/") && !strings.Contains(msg, "/register/") {
		return
	}
	raw := loginURLRe.FindString(msg)
	if raw == "" {
		return
	}
	raw = strings.TrimRight(raw, ".,);]")
	mu.Lock()
	defer mu.Unlock()
	if gen != generation {
		return
	}
	loginURL = raw
	if state == "starting" {
		state = "needs_login"
		log.Printf("tsbridge: needs_login")
	}
}

// swappingHandler forwards to the current reverse proxy. Proxy swaps the
// inner handler when the target changes, so the listener stays bound.
type swappingHandler struct {
	mu sync.RWMutex
	h  http.Handler
}

func (s *swappingHandler) swap(h http.Handler) {
	s.mu.Lock()
	s.h = h
	s.mu.Unlock()
}

func (s *swappingHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mu.RLock()
	h := s.h
	s.mu.RUnlock()
	if h == nil {
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return
	}
	h.ServeHTTP(w, r)
}

func installProxy(target string, port int, dial dialFunc) (int, error) {
	h, err := proxyHandlerFor(target, dial)
	if err != nil {
		return 0, err
	}
	mu.Lock()
	defer mu.Unlock()
	if proxySrv != nil && proxyHandler != nil {
		if port != 0 && port != proxyPort {
			return 0, fmt.Errorf("tsbridge: proxy already listening on 127.0.0.1:%d", proxyPort)
		}
		proxyHandler.swap(h)
		log.Printf("tsbridge: proxy target updated on 127.0.0.1:%d", proxyPort)
		return proxyPort, nil
	}
	got, srv, sh, err := listenProxy(h, port)
	if err != nil {
		return 0, err
	}
	proxySrv = srv
	proxyHandler = sh
	proxyPort = got
	return got, nil
}

// startProxy serves a reverse proxy of target on 127.0.0.1:port.
// port 0 is ephemeral. dial is tsnet.Server.Dial in production and a plain
// net.Dialer in tests. The returned server is independent of the Proxy singleton.
func startProxy(target string, port int, dial dialFunc) (int, *http.Server, error) {
	h, err := proxyHandlerFor(target, dial)
	if err != nil {
		return 0, nil, err
	}
	got, srv, _, err := listenProxy(h, port)
	if err != nil {
		return 0, nil, err
	}
	return got, srv, nil
}

func proxyHandlerFor(target string, dial dialFunc) (http.Handler, error) {
	rp, err := newReverseProxy(target, dial)
	if err != nil {
		return nil, err
	}
	return loopbackOnly(requireBridgeSecret(rp)), nil
}

// listenProxy binds 127.0.0.1:port. A non-zero port is used exactly; if it
// is taken, or the kernel returns a different port, the error is returned
// and nothing is left listening.
func listenProxy(handler http.Handler, port int) (int, *http.Server, *swappingHandler, error) {
	if port < 0 || port > 65535 {
		return 0, nil, nil, fmt.Errorf("tsbridge: invalid port %d", port)
	}
	ln, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return 0, nil, nil, fmt.Errorf("tsbridge: listen 127.0.0.1:%d: %w", port, err)
	}
	got := ln.Addr().(*net.TCPAddr).Port
	if port != 0 && got != port {
		_ = ln.Close()
		return 0, nil, nil, fmt.Errorf("tsbridge: listened on 127.0.0.1:%d, want %d", got, port)
	}
	sh := &swappingHandler{}
	sh.swap(handler)
	srv := &http.Server{
		Handler:           sh,
		ReadHeaderTimeout: 30 * time.Second,
	}
	go func() {
		err := srv.Serve(ln)
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("tsbridge: proxy serve: %v", err)
		}
	}()
	log.Printf("tsbridge: proxy on 127.0.0.1:%d", got)
	return got, srv, sh, nil
}

func newReverseProxy(target string, dial dialFunc) (*httputil.ReverseProxy, error) {
	if dial == nil {
		return nil, errors.New("tsbridge: no dialer")
	}
	u, err := url.Parse(target)
	if err != nil {
		return nil, fmt.Errorf("tsbridge: target: %w", err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return nil, fmt.Errorf("tsbridge: unsupported scheme %q", u.Scheme)
	}
	if u.Host == "" || u.Hostname() == "" {
		return nil, fmt.Errorf("tsbridge: target %q has no host", target)
	}

	transport := &http.Transport{
		Proxy: nil,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			// tsnet.Dial resolves MagicDNS names inside the userspace stack.
			// Force TCP. WebSocket upgrades and plain HTTP are both TCP streams.
			if network != "tcp" && network != "tcp4" && network != "tcp6" {
				network = "tcp"
			}
			return dial(ctx, network, addr)
		},
		ForceAttemptHTTP2: false,
		// An empty map disables HTTP/2. WebSocket upgrades need HTTP/1.1 so the
		// response body is an io.ReadWriteCloser that ReverseProxy can hijack.
		TLSNextProto:          make(map[string]func(string, *tls.Conn) http.RoundTripper),
		MaxIdleConns:          32,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ExpectContinueTimeout: time.Second,
		// Do not buffer response bodies. Streaming handlers and server-sent
		// events flush on each write. ReverseProxy.FlushInterval -1 does the
		// same on the way back to the WebView.
		DisableCompression: true,
	}
	if u.Scheme == "https" {
		transport.TLSClientConfig = &tls.Config{
			MinVersion: tls.VersionTLS12,
			ServerName: u.Hostname(),
		}
	}

	host := u.Host
	return &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(u)
			// SetURL clears Host. Put the target host back so the Lumen server
			// sees its own name, not 127.0.0.1.
			pr.Out.Host = host
			// The inbound handler already drops lumen_bridge. Drop it again on
			// the outbound request so a later change to that handler cannot
			// forward the per-install secret.
			stripBridgeCookie(pr.Out.Header)
		},
		Transport: transport,
		// Negative means flush every write. httputil.ReverseProxy also
		// re-adds Connection: Upgrade and Upgrade after stripping hop-by-hop
		// headers, then hijacks on 101 Switching Protocols.
		FlushInterval: -1,
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			log.Printf("tsbridge: proxy: %v", err)
			http.Error(w, "bad gateway", http.StatusBadGateway)
		},
	}, nil
}

func loopbackOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !fromLoopback(r.RemoteAddr) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// requireBridgeSecret rejects every request when a long enough secret is
// installed and the lumen_bridge cookie does not match. A missing secret
// keeps the previous open behavior and logs that once.
func requireBridgeSecret(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		secret := currentBridgeSecret()
		if len(secret) < bridgeSecretMinLen {
			bridgeWarnOnce.Do(func() {
				log.Printf("tsbridge: proxy secret is unset; loopback clients are not authenticated")
			})
			stripBridgeCookie(r.Header)
			next.ServeHTTP(w, r)
			return
		}
		if subtle.ConstantTimeCompare([]byte(bridgeCookieValue(r)), []byte(secret)) != 1 {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		stripBridgeCookie(r.Header)
		next.ServeHTTP(w, r)
	})
}

func bridgeCookieValue(r *http.Request) string {
	c, err := r.Cookie(bridgeCookieName)
	if err != nil {
		return ""
	}
	return c.Value
}

// stripBridgeCookie rebuilds Cookie without lumen_bridge. An empty result
// drops the header so the upstream request does not carry a blank Cookie line.
func stripBridgeCookie(h http.Header) {
	if len(h) == 0 {
		return
	}
	vals := h.Values("Cookie")
	if len(vals) == 0 {
		return
	}
	kept := make([]string, 0, len(vals))
	for _, v := range vals {
		for _, part := range strings.Split(v, ";") {
			trimmed := strings.TrimSpace(part)
			if trimmed == "" {
				continue
			}
			name, _, ok := strings.Cut(trimmed, "=")
			if ok && strings.TrimSpace(name) == bridgeCookieName {
				continue
			}
			kept = append(kept, trimmed)
		}
	}
	if len(kept) == 0 {
		h.Del("Cookie")
		return
	}
	h.Set("Cookie", strings.Join(kept, "; "))
}

func fromLoopback(remoteAddr string) bool {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	ip4 := ip.To4()
	return ip4 != nil && ip4.Equal(net.IPv4(127, 0, 0, 1))
}
