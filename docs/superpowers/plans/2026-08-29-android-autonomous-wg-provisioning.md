# Android Autonomous WireGuard Provisioning — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android's portal-login flow gets a fully autonomous WireGuard tunnel — the portal generates and persists a permanent WG peer on first config fetch, Android just plugs the returned `.conf` string into its existing `ClientConfig` fields, and no one ever touches `WireGuardConfigCard` for this path again.

**Architecture:** `vkturn-ios-portal` (Go, on host `vps`) gains a `device=android`-only step inside its existing `/api/v1/config` handler: allocate-once, then idempotently re-assert (self-heal) a WireGuard peer, and return a ready `.conf` string. `panel.js` (on host `ai-lan`) stops managing WG for the `androidlogin` device type it already has. `turn-proxy-android` (this repo) copies that one new field into two existing `ClientConfig` fields it already understands.

**Tech Stack:** Go 1.x (`vkturn-ios-portal`), Node.js (`panel.js`, no framework), Kotlin/Compose (`turn-proxy-android`).

**Spec:** `docs/superpowers/specs/2026-08-29-android-autonomous-wg-provisioning-design.md`

## Global Constraints

- Android's WG peer is **permanent** — never rotated, unlike iOS's 8h `handleGenerate` cycle. Regeneration happens exactly once, gated on `AndroidWGPrivkey == ""`.
- Re-asserting the peer (`wg set` + conf rewrite) must be safe to run on every `/api/v1/config?device=android` call — it must never invalidate an already-connected device's credentials (see spec's clarification on this).
- `usersMu` must cover the *entire* read-modify-write-`wg set`-file-rewrite sequence in `handleAPIConfig`'s android branch, not just the `loadUsers` call — concurrent requests must not race on `wgcl.conf`.
- iOS's existing `WGIP`/`WGPubkey`/`handleGenerate`/`swapPeer` behavior must not change. Android gets its own fields (`AndroidWGIP`/`AndroidWGPubkey`/`AndroidWGPrivkey`) and its own `wgcl.conf` marker (`# ios-portal-android:<username>`, not `# ios-portal:<username>`).
- `.fabackup` (`genArtifact('android')` in `panel.js`) is explicitly **not touched** by this work — stays as a fallback path, removed later by separate decision.
- **Hard gate before this is considered done:** a debug build with these changes must be verified on the user's own physical Android device — WireGuard coming up with zero manual steps, real traffic flowing — before `panel.js`/portal changes are treated as ready for family rollout. Passing `go test`/curl checks is necessary but not sufficient.

---

## Task 1: CIDR-subtraction math, ported from `make-backup.js`

**Files:**
- Create: `/home/lev/vkturn-ios-portal/cidr.go`
- Test: `/home/lev/vkturn-ios-portal/cidr_test.go`

**Interfaces:**
- Produces: `cidrRange(cidr string) (start, end uint64, err error)`, `rangeToCidrs(start, end uint64) []string`, `allowedIpsExcluding(excludeCIDRs []string) ([]string, error)` — all package `main`, consumed by Task 5's `buildAndroidWGConf`.

- [ ] **Step 1: Write the failing tests**

```go
// cidr_test.go
package main

import "testing"

func TestAllowedIpsExcludingEmpty(t *testing.T) {
	got, err := allowedIpsExcluding(nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0] != "0.0.0.0/0" {
		t.Fatalf("expected [0.0.0.0/0], got %v", got)
	}
}

func totalAddresses(t *testing.T, cidrs []string) uint64 {
	t.Helper()
	var total uint64
	for _, c := range cidrs {
		s, e, err := cidrRange(c)
		if err != nil {
			t.Fatal(err)
		}
		total += e - s + 1
	}
	return total
}

func overlapsRange(t *testing.T, cidrs []string, exclStart, exclEnd uint64) bool {
	t.Helper()
	for _, c := range cidrs {
		s, e, err := cidrRange(c)
		if err != nil {
			t.Fatal(err)
		}
		if s <= exclEnd && e >= exclStart {
			return true
		}
	}
	return false
}

func TestAllowedIpsExcludingSingleRange(t *testing.T) {
	got, err := allowedIpsExcluding([]string{"10.0.0.0/8"})
	if err != nil {
		t.Fatal(err)
	}
	exclStart, exclEnd, err := cidrRange("10.0.0.0/8")
	if err != nil {
		t.Fatal(err)
	}
	if overlapsRange(t, got, exclStart, exclEnd) {
		t.Fatalf("output overlaps excluded range 10.0.0.0/8: %v", got)
	}
	const totalV4 = uint64(1) << 32
	excludedSize := exclEnd - exclStart + 1
	if total := totalAddresses(t, got); total != totalV4-excludedSize {
		t.Fatalf("expected %d addresses covered, got %d", totalV4-excludedSize, total)
	}
}

func TestAllowedIpsExcludingProductionCIDRs(t *testing.T) {
	exclude := []string{"95.163.0.0/16", "90.156.0.0/16"}
	got, err := allowedIpsExcluding(exclude)
	if err != nil {
		t.Fatal(err)
	}
	var excludedTotal uint64
	for _, c := range exclude {
		s, e, err := cidrRange(c)
		if err != nil {
			t.Fatal(err)
		}
		excludedTotal += e - s + 1
		if overlapsRange(t, got, s, e) {
			t.Fatalf("output overlaps excluded range %s: %v", c, got)
		}
	}
	const totalV4 = uint64(1) << 32
	if total := totalAddresses(t, got); total != totalV4-excludedTotal {
		t.Fatalf("expected %d addresses covered, got %d", totalV4-excludedTotal, total)
	}
}

func TestCidrRangeRoundTrip(t *testing.T) {
	cases := []struct {
		cidr               string
		wantStart, wantEnd uint64
	}{
		{"10.0.0.0/8", 167772160, 184549375},
		{"0.0.0.0/0", 0, 4294967295},
		{"192.168.1.1/32", 3232235777, 3232235777},
	}
	for _, c := range cases {
		s, e, err := cidrRange(c.cidr)
		if err != nil {
			t.Fatalf("%s: %v", c.cidr, err)
		}
		if s != c.wantStart || e != c.wantEnd {
			t.Fatalf("%s: got [%d,%d], want [%d,%d]", c.cidr, s, e, c.wantStart, c.wantEnd)
		}
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -run 'TestAllowedIpsExcluding|TestCidrRangeRoundTrip' -v`
Expected: build failure — `cidrRange`/`allowedIpsExcluding` undefined (`cidr.go` doesn't exist yet).

- [ ] **Step 3: Write the implementation**

```go
// cidr.go
package main

import (
	"fmt"
	"math/bits"
	"net"
	"sort"
	"strconv"
	"strings"
)

// ipToInt/intToIP convert between dotted-quad and a uint32 in network byte
// order. Pure port of make-backup.js's ipToInt/intToIp (panel.js) - same
// mapping, needed here so the portal can build the same AllowedIPs math
// server-side that panel.js has always built for the manual .fabackup path.
func ipToInt(ip string) (uint32, error) {
	parsed := net.ParseIP(ip).To4()
	if parsed == nil {
		return 0, fmt.Errorf("not an IPv4 address: %q", ip)
	}
	return uint32(parsed[0])<<24 | uint32(parsed[1])<<16 | uint32(parsed[2])<<8 | uint32(parsed[3]), nil
}

func intToIP(n uint32) string {
	return fmt.Sprintf("%d.%d.%d.%d", byte(n>>24), byte(n>>16), byte(n>>8), byte(n))
}

// cidrRange returns [start, end] inclusive as uint64 (not uint32) so that
// end can legitimately reach 2^32-1 and "+1" arithmetic on it in
// allowedIpsExcluding never wraps.
func cidrRange(c string) (start, end uint64, err error) {
	ip, bitsStr, hasSlash := strings.Cut(c, "/")
	maskBits := 32
	if hasSlash {
		maskBits, err = strconv.Atoi(bitsStr)
		if err != nil {
			return 0, 0, fmt.Errorf("bad CIDR %q: %w", c, err)
		}
	}
	base, err := ipToInt(ip)
	if err != nil {
		return 0, 0, err
	}
	size := uint64(1) << uint(32-maskBits)
	s := uint64(base) - uint64(base)%size
	return s, s + size - 1, nil
}

// rangeToCidrs splits [start, end] (inclusive) into the minimal set of
// CIDR blocks covering exactly that range - direct port of
// make-backup.js's rangeToCidrs (same greedy bit-alignment algorithm;
// floorLog2(n) = bits.Len64(n)-1 replaces JS's Math.floor(Math.log2(n))).
func rangeToCidrs(start, end uint64) []string {
	var out []string
	for start <= end {
		maxSize := 32
		for maxSize > 0 {
			m := uint64(1) << uint(32-(maxSize-1))
			if start%m != 0 {
				break
			}
			maxSize--
		}
		n := end - start + 1
		floorLog2 := bits.Len64(n) - 1
		spanBits := 32 - floorLog2
		if spanBits < maxSize {
			spanBits = maxSize
		}
		out = append(out, fmt.Sprintf("%s/%d", intToIP(uint32(start)), spanBits))
		start += uint64(1) << uint(32-spanBits)
		if start == 0 {
			break
		}
	}
	return out
}

// allowedIpsExcluding returns AllowedIPs entries covering all of IPv4
// (0.0.0.0/0) minus excludeCIDRs - port of make-backup.js's function of the
// same name. Needed because the core subprocess's own sockets (TURN dial,
// hub-creds fetch) can't be protect()-ed from the phone's own WireGuard
// tunnel (the core runs as a subprocess, not gomobile code in the same
// process) - without this exclusion the tunnel routes its own control
// traffic through itself and never works. See docs/superpowers/specs/
// 2026-08-29-android-autonomous-wg-provisioning-design.md, component 3.
func allowedIpsExcluding(excludeCIDRs []string) ([]string, error) {
	if len(excludeCIDRs) == 0 {
		return []string{"0.0.0.0/0"}, nil
	}
	type rng struct{ s, e uint64 }
	holes := make([]rng, 0, len(excludeCIDRs))
	for _, c := range excludeCIDRs {
		s, e, err := cidrRange(c)
		if err != nil {
			return nil, err
		}
		holes = append(holes, rng{s, e})
	}
	sort.Slice(holes, func(i, j int) bool { return holes[i].s < holes[j].s })

	var out []string
	var cur uint64
	for _, h := range holes {
		if h.s > cur {
			out = append(out, rangeToCidrs(cur, h.s-1)...)
		}
		if h.e+1 > cur {
			cur = h.e + 1
		}
	}
	const maxV4 = uint64(1)<<32 - 1
	if cur <= maxV4 {
		out = append(out, rangeToCidrs(cur, maxV4)...)
	}
	return out, nil
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -run 'TestAllowedIpsExcluding|TestCidrRangeRoundTrip' -v`
Expected: all PASS.

- [ ] **Step 5: Commit is deferred** — this repo has no git; Task 1's files are committed as part of the batch copy-to-`vps` in Task 7. Keep working locally.

---

## Task 2: Test-infra fixes — real `WGConfPath`, `wg set` test seam, `rewritePeerBlock` marker parameter

**Files:**
- Modify: `/home/lev/vkturn-ios-portal/main.go` (`Config` struct, `rewritePeerBlock`, `swapPeer`, `removePeer`, new `(*Config).applyWGPeer`)
- Modify: `/home/lev/vkturn-ios-portal/main_test.go` (`testConfig` helper, new test)

**Interfaces:**
- Consumes: nothing new.
- Produces: `Config.wgSetPeer func(pubkey, ip string) error` (unexported test seam, nil in production), `(*Config).applyWGPeer(pubkey, ip string) error`, `rewritePeerBlock(cfg *Config, markerPrefix, username, newPub, ip string) error` (signature changed — was `rewritePeerBlock(cfg, username, newPub, ip)`). Task 4 depends on `applyWGPeer` and the new `rewritePeerBlock` signature.

- [ ] **Step 1: Write the failing test** (proves the marker-prefix parameter works and that two different prefixes for the same username don't clobber each other)

```go
// main_test.go — add
func TestRewritePeerBlockMarkerPrefix(t *testing.T) {
	dir := t.TempDir()
	confPath := filepath.Join(dir, "wgcl.conf")
	if err := os.WriteFile(confPath, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{WGConfPath: confPath}

	if err := rewritePeerBlock(cfg, "ios-portal", "vasya", "pubkey-ios", "10.13.13.5"); err != nil {
		t.Fatal(err)
	}
	if err := rewritePeerBlock(cfg, "ios-portal-android", "vasya", "pubkey-android", "10.13.13.6"); err != nil {
		t.Fatal(err)
	}

	b, err := os.ReadFile(confPath)
	if err != nil {
		t.Fatal(err)
	}
	content := string(b)
	if !strings.Contains(content, "pubkey-ios") {
		t.Fatalf("ios-portal block was clobbered by the android write:\n%s", content)
	}
	if !strings.Contains(content, "pubkey-android") {
		t.Fatalf("android block missing:\n%s", content)
	}

	// Rewriting the ios-portal block again must not touch the android one.
	if err := rewritePeerBlock(cfg, "ios-portal", "vasya", "pubkey-ios-v2", "10.13.13.5"); err != nil {
		t.Fatal(err)
	}
	b, _ = os.ReadFile(confPath)
	content = string(b)
	if strings.Contains(content, "pubkey-ios\n") {
		t.Fatalf("expected old ios pubkey replaced:\n%s", content)
	}
	if !strings.Contains(content, "pubkey-android") {
		t.Fatalf("android block was clobbered by the ios rewrite:\n%s", content)
	}
}
```

Also add `"strings"` and `"fmt"` to `main_test.go`'s import block (used by this test and Task 4's tests).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -run TestRewritePeerBlockMarkerPrefix -v`
Expected: build failure — `rewritePeerBlock` called with 5 args, current signature takes 4 (no `markerPrefix`).

- [ ] **Step 3: Update `rewritePeerBlock`, `swapPeer`, `removePeer`, add `applyWGPeer` + test seam**

In `main.go`, replace the existing `rewritePeerBlock` (currently hardcodes `"# ios-portal:" + username`):

```go
// rewritePeerBlock replaces (or removes, if newPub is empty) the
// "# <markerPrefix>:<username>" tagged [Peer] block in the on-disk conf
// file. markerPrefix distinguishes iOS's peer ("ios-portal") from
// Android's ("ios-portal-android") so the same person having both device
// types never clobbers the other's block via this regex.
func rewritePeerBlock(cfg *Config, markerPrefix, username, newPub, ip string) error {
	b, err := os.ReadFile(cfg.WGConfPath)
	if err != nil {
		return fmt.Errorf("read wg conf: %w", err)
	}
	marker := "# " + markerPrefix + ":" + username
	block := regexp.MustCompile(`(?s)\[Peer\]\n` + regexp.QuoteMeta(marker) + `\n.*?(\n\n|\z)`)
	stripped := block.ReplaceAll(b, []byte{})
	out := bytes.TrimRight(stripped, "\n")
	if newPub != "" {
		newBlock := fmt.Sprintf("\n[Peer]\n%s\nPublicKey = %s\nAllowedIPs = %s/32\n", marker, newPub, ip)
		out = append(out, []byte(newBlock)...)
	} else {
		out = append(out, '\n')
	}

	tmp := cfg.WGConfPath + ".tmp"
	if err := os.WriteFile(tmp, out, 0o600); err != nil {
		return fmt.Errorf("write wg conf: %w", err)
	}
	return os.Rename(tmp, cfg.WGConfPath)
}
```

Update `swapPeer` (only existing caller) to pass the iOS marker explicitly:

```go
func swapPeer(cfg *Config, username, oldPub, newPub, ip string) error {
	if oldPub != "" {
		_ = exec.Command("wg", "set", cfg.WGIface, "peer", oldPub, "remove").Run()
	}
	if out, err := exec.Command("wg", "set", cfg.WGIface, "peer", newPub, "allowed-ips", ip+"/32").CombinedOutput(); err != nil {
		return fmt.Errorf("wg set peer: %w (%s)", err, out)
	}
	return rewritePeerBlock(cfg, "ios-portal", username, newPub, ip)
}
```

Update `removePeer` the same way:

```go
func removePeer(cfg *Config, username, pub string) error {
	_ = exec.Command("wg", "set", cfg.WGIface, "peer", pub, "remove").Run()
	return rewritePeerBlock(cfg, "ios-portal", username, "", "")
}
```

Add the test seam to `Config` (near the bottom of the struct, main.go:45-64) and the method next to `swapPeer`/`removePeer`:

```go
// inside `type Config struct { ... }`, after DNSServers:
	WGExcludeCIDRs []string `json:"wg_exclude_cidrs,omitempty"` // e.g. VK's own ranges - see allowedIpsExcluding

	// wgSetPeer, if set, replaces the real `wg set` exec call in
	// applyWGPeer - test-only seam (unexported, JSON-invisible) so tests
	// can exercise ensureAndroidWGPeer's logic without a real wgcl
	// interface. Nil in production.
	wgSetPeer func(pubkey, ip string) error
```

```go
// applyWGPeer registers pubkey/ip live on cfg.WGIface, idempotently -
// see ensureAndroidWGPeer (Task 4) for why this must be safe to call on
// every request, not just once.
func (cfg *Config) applyWGPeer(pubkey, ip string) error {
	if cfg.wgSetPeer != nil {
		return cfg.wgSetPeer(pubkey, ip)
	}
	out, err := exec.Command("wg", "set", cfg.WGIface, "peer", pubkey, "allowed-ips", ip+"/32").CombinedOutput()
	if err != nil {
		return fmt.Errorf("wg set peer: %w (%s)", err, out)
	}
	return nil
}
```

In `main_test.go`, update `testConfig` to give every test a real, writable `WGConfPath` and a no-op `wgSetPeer` (currently `/tmp/wg.conf` doesn't exist, so anything touching `rewritePeerBlock` would fail with "read wg conf: no such file"; and without the seam, anything touching `applyWGPeer` would fail with "no such device" on any machine that isn't `vps`):

```go
func testConfig(t *testing.T, usersFile string) *Config {
	t.Helper()
	wgConfPath := filepath.Join(filepath.Dir(usersFile), "wgcl.conf")
	if err := os.WriteFile(wgConfPath, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	return &Config{
		Listen: "x", CertFile: "x", KeyFile: "x",
		CookieSecret: "00112233445566778899aabbccddeeff00112233445566778899aabbccddee",
		ObfKeyHex:    "abc", PeerAddress: "1.2.3.4:56000",
		WGIface: "wgcl", WGServerPubkey: "pub", WGConfPath: wgConfPath,
		WGSubnetPrefix: "10.13.13.", PoolDir: "/tmp/pool", UsersFile: usersFile,
		HubHost: "1.2.3.4", HubToken: "tok123", HubPin: "pin123",
		wgSetPeer: func(pubkey, ip string) error { return nil },
	}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -v`
Expected: all PASS, including every pre-existing test (they now get a real `WGConfPath` + no-op `wgSetPeer`, neither of which they previously exercised, so this must not change their outcomes).

---

## Task 3: `User`/`Config` data model + `nextFreeIP` extension

**Files:**
- Modify: `/home/lev/vkturn-ios-portal/main.go` (`User` struct, `nextFreeIP`)
- Modify: `/home/lev/vkturn-ios-portal/main_test.go` (new test)

**Interfaces:**
- Produces: `User.AndroidWGIP`, `User.AndroidWGPubkey`, `User.AndroidWGPrivkey` (all `string`) — Task 4 reads/writes these.

- [ ] **Step 1: Write the failing test**

```go
// main_test.go — add
func TestNextFreeIPSkipsAndroidOctets(t *testing.T) {
	cfg := &Config{WGSubnetPrefix: "10.13.13.", WGConfPath: filepath.Join(t.TempDir(), "wgcl.conf")}
	if err := os.WriteFile(cfg.WGConfPath, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	users := []User{
		{Username: "ios1", WGIP: "10.13.13.2"},
		{Username: "android1", AndroidWGIP: "10.13.13.3"},
	}
	ip, err := nextFreeIP(cfg, users)
	if err != nil {
		t.Fatal(err)
	}
	if ip != "10.13.13.4" {
		t.Fatalf("expected 10.13.13.4 (2 and 3 taken), got %s", ip)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -run TestNextFreeIPSkipsAndroidOctets -v`
Expected: FAIL — `nextFreeIP` returns `10.13.13.3` (doesn't know about `AndroidWGIP` yet, only octet 2 is seen as taken).

- [ ] **Step 3: Add the fields and extend `nextFreeIP`**

In `User` struct (main.go, right after the existing `AndroidStreams int` field):

```go
	// AndroidWG* provision a permanent WireGuard identity for android-login
	// users, generated and stored server-side, automatically - see
	// docs/superpowers/specs/2026-08-29-android-autonomous-wg-provisioning-design.md.
	// Unlike iOS's WGIP/WGPubkey, AndroidWGPrivkey IS persisted: this peer
	// never rotates, so the server must be able to hand back the SAME
	// identity on every future config fetch, not just once.
	AndroidWGIP      string `json:"android_wg_ip,omitempty"`
	AndroidWGPubkey  string `json:"android_wg_pubkey,omitempty"`
	AndroidWGPrivkey string `json:"android_wg_privkey,omitempty"`
```

In `nextFreeIP` (main.go), add the second scan line right after the existing `WGIP` one:

```go
	for _, u := range users {
		if n, ok := octetOf(cfg.WGSubnetPrefix, u.WGIP); ok {
			taken[n] = true
		}
		if n, ok := octetOf(cfg.WGSubnetPrefix, u.AndroidWGIP); ok {
			taken[n] = true
		}
	}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -v`
Expected: all PASS.

---

## Task 4: `ensureAndroidWGPeer` — allocate-once, self-heal-always

**Files:**
- Modify: `/home/lev/vkturn-ios-portal/main.go` (new function, near `wgGenKeypair`/`swapPeer`)
- Modify: `/home/lev/vkturn-ios-portal/main_test.go` (new tests)

**Interfaces:**
- Consumes: `nextFreeIP` (Task 3), `wgGenKeypair` (existing), `(*Config).applyWGPeer` (Task 2), `rewritePeerBlock` (Task 2, new signature).
- Produces: `ensureAndroidWGPeer(cfg *Config, users []User, u *User) (privkey string, err error)` — Task 6 calls this from `handleAPIConfig`.

- [ ] **Step 1: Write the failing tests**

```go
// main_test.go — add
func TestEnsureAndroidWGPeerGeneratesOnce(t *testing.T) {
	dir := t.TempDir()
	usersFile := filepath.Join(dir, "users.json")
	cfg := testConfig(t, usersFile)
	users := []User{{Username: "vasya", AndroidAccounts: []int{8445}}}
	u := &users[0]

	priv1, err := ensureAndroidWGPeer(cfg, users, u)
	if err != nil {
		t.Fatal(err)
	}
	if priv1 == "" || u.AndroidWGPubkey == "" || u.AndroidWGIP == "" {
		t.Fatalf("expected keys/ip to be populated, got %+v", u)
	}

	priv2, err := ensureAndroidWGPeer(cfg, users, u)
	if err != nil {
		t.Fatal(err)
	}
	if priv2 != priv1 {
		t.Fatalf("second call regenerated the private key: %q != %q", priv2, priv1)
	}
}

func TestEnsureAndroidWGPeerCallsApplyEveryTime(t *testing.T) {
	dir := t.TempDir()
	usersFile := filepath.Join(dir, "users.json")
	cfg := testConfig(t, usersFile)
	calls := 0
	cfg.wgSetPeer = func(pubkey, ip string) error { calls++; return nil }
	users := []User{{Username: "vasya", AndroidAccounts: []int{8445}}}
	u := &users[0]

	if _, err := ensureAndroidWGPeer(cfg, users, u); err != nil {
		t.Fatal(err)
	}
	if _, err := ensureAndroidWGPeer(cfg, users, u); err != nil {
		t.Fatal(err)
	}
	if calls != 2 {
		t.Fatalf("expected applyWGPeer called on every invocation (self-healing), got %d calls", calls)
	}
}

func TestEnsureAndroidWGPeerPropagatesApplyError(t *testing.T) {
	dir := t.TempDir()
	usersFile := filepath.Join(dir, "users.json")
	cfg := testConfig(t, usersFile)
	cfg.wgSetPeer = func(pubkey, ip string) error { return fmt.Errorf("boom") }
	users := []User{{Username: "vasya", AndroidAccounts: []int{8445}}}
	u := &users[0]

	if _, err := ensureAndroidWGPeer(cfg, users, u); err == nil {
		t.Fatal("expected error to propagate when wg set fails")
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -run TestEnsureAndroidWGPeer -v`
Expected: build failure — `ensureAndroidWGPeer` undefined.

- [ ] **Step 3: Write the implementation**

```go
// main.go — add near wgGenKeypair/swapPeer/removePeer
//
// ensureAndroidWGPeer allocates (once) and (re)asserts (every call -
// idempotent, self-healing) a permanent WireGuard peer for an
// android-login user. Unlike swapPeer (iOS, rotates on every Generate
// click), never removes an existing peer - Android's identity is
// permanent by design (see the spec's "Жизненный цикл пира" section).
// Mutates u in place; the caller is responsible for saveUsers under
// usersMu (see handleAPIConfig, Task 6) - this function does not persist
// anything to disk itself beyond the wgcl.conf peer block.
func ensureAndroidWGPeer(cfg *Config, users []User, u *User) (privkey string, err error) {
	if u.AndroidWGPrivkey == "" {
		ip, err := nextFreeIP(cfg, users)
		if err != nil {
			return "", fmt.Errorf("allocate android wg ip: %w", err)
		}
		priv, pub, err := wgGenKeypair()
		if err != nil {
			return "", fmt.Errorf("android wg keygen: %w", err)
		}
		u.AndroidWGIP = ip
		u.AndroidWGPubkey = pub
		u.AndroidWGPrivkey = priv
	}
	if err := cfg.applyWGPeer(u.AndroidWGPubkey, u.AndroidWGIP); err != nil {
		return "", fmt.Errorf("apply android wg peer: %w", err)
	}
	if err := rewritePeerBlock(cfg, "ios-portal-android", u.Username, u.AndroidWGPubkey, u.AndroidWGIP); err != nil {
		return "", fmt.Errorf("persist android wg peer to conf: %w", err)
	}
	return u.AndroidWGPrivkey, nil
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -v`
Expected: all PASS.

---

## Task 5: `buildAndroidWGConf` — pure `.conf` text builder

**Files:**
- Modify: `/home/lev/vkturn-ios-portal/main.go` (new function)
- Modify: `/home/lev/vkturn-ios-portal/main_test.go` (new test)

**Interfaces:**
- Consumes: `allowedIpsExcluding` (Task 1).
- Produces: `buildAndroidWGConf(cfg *Config, priv, ip string, excludeCIDRs []string) (string, error)` — Task 6 calls this.

- [ ] **Step 1: Write the failing test**

```go
// main_test.go — add
func TestBuildAndroidWGConf(t *testing.T) {
	cfg := &Config{WGServerPubkey: "serverpub", DNSServers: "1.1.1.1"}
	conf, err := buildAndroidWGConf(cfg, "privkey123", "10.13.13.7", []string{"95.163.0.0/16"})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"PrivateKey = privkey123",
		"Address = 10.13.13.7/32",
		"DNS = 1.1.1.1",
		"PublicKey = serverpub",
		"Endpoint = 127.0.0.1:9000",
		"PersistentKeepalive = 25",
	} {
		if !strings.Contains(conf, want) {
			t.Fatalf("conf missing %q:\n%s", want, conf)
		}
	}
	if strings.Contains(conf, "95.163.") {
		t.Fatalf("conf should exclude 95.163.0.0/16 from AllowedIPs:\n%s", conf)
	}
	if !strings.Contains(conf, "::/0") {
		t.Fatalf("conf missing IPv6 blackhole:\n%s", conf)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -run TestBuildAndroidWGConf -v`
Expected: build failure — `buildAndroidWGConf` undefined.

- [ ] **Step 3: Write the implementation**

```go
// main.go — add near ensureAndroidWGPeer
//
// buildAndroidWGConf renders a ready-to-use WireGuard .conf for an
// android-login user. Pure string formatting, no I/O - testable without
// wg/wgcl. Endpoint is a fixed local convention (the core subprocess's
// own -listen on the same phone, matching panel.js's identical convention
// for the .fabackup path), not a config value.
func buildAndroidWGConf(cfg *Config, priv, ip string, excludeCIDRs []string) (string, error) {
	allowed, err := allowedIpsExcluding(excludeCIDRs)
	if err != nil {
		return "", err
	}
	allowed = append(allowed, "::/0")
	dns := cfg.DNSServers
	if dns == "" {
		dns = "1.1.1.1"
	}
	return fmt.Sprintf(
		"[Interface]\nPrivateKey = %s\nAddress = %s/32\nDNS = %s\n\n[Peer]\nPublicKey = %s\nAllowedIPs = %s\nEndpoint = 127.0.0.1:9000\nPersistentKeepalive = 25",
		priv, ip, dns, cfg.WGServerPubkey, strings.Join(allowed, ", "),
	), nil
}
```

`strings` is already imported in `main.go` (used elsewhere); no new import needed there.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -v`
Expected: all PASS.

---

## Task 6: Wire it into `handleAPIConfig` + `DesktopConfig.WgConfig`

**Files:**
- Modify: `/home/lev/vkturn-ios-portal/api.go` (`DesktopConfig` struct, `handleAPIConfig`)
- Modify: `/home/lev/vkturn-ios-portal/api_test.go` (extend existing tests, add one)

**Interfaces:**
- Consumes: `ensureAndroidWGPeer` (Task 4), `buildAndroidWGConf` (Task 5).
- Produces: `DesktopConfig.WgConfig string` (JSON `wgConfig,omitempty`) — Task 9 (Android) parses this field.

- [ ] **Step 1: Write the failing tests**

In `api_test.go`, extend `TestHandleAPIConfig` (desktop path — proves desktop never gets a wgConfig) by adding this assertion right after the existing ones:

```go
	if got.WgConfig != "" {
		t.Fatalf("expected no wgConfig for desktop, got %q", got.WgConfig)
	}
```

Extend `TestHandleAPIConfigAndroid` by adding these assertions after the existing `got.Streams` check:

```go
	if got.WgConfig == "" {
		t.Fatal("expected wgConfig to be populated for android")
	}
	if !strings.Contains(got.WgConfig, "[Interface]") || !strings.Contains(got.WgConfig, "[Peer]") {
		t.Fatalf("wgConfig doesn't look like a WireGuard conf:\n%s", got.WgConfig)
	}
```

Add a new test proving permanence across repeated fetches:

```go
func TestHandleAPIConfigAndroidIdempotent(t *testing.T) {
	dir := t.TempDir()
	usersFile := filepath.Join(dir, "users.json")
	seedUser(t, usersFile, "vasya", "correct-horse", User{
		AccountID: "acct1", AndroidAccounts: []int{8445}, AndroidStreams: 8,
	})
	cfg := testConfig(t, usersFile)

	loginReq := httptest.NewRequest(http.MethodPost, "/api/v1/login",
		strings.NewReader(`{"username":"vasya","password":"correct-horse"}`))
	loginW := httptest.NewRecorder()
	handleAPILogin(cfg)(loginW, loginReq)
	var loginResp struct {
		Token string `json:"token"`
	}
	_ = json.Unmarshal(loginW.Body.Bytes(), &loginResp)

	fetch := func() DesktopConfig {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/config?device=android", nil)
		req.Header.Set("Authorization", "Bearer "+loginResp.Token)
		w := httptest.NewRecorder()
		handleAPIConfig(cfg)(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
		}
		var got DesktopConfig
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		return got
	}

	first := fetch()
	second := fetch()
	if first.WgConfig != second.WgConfig {
		t.Fatalf("wgConfig changed between calls - identity should be permanent:\nfirst:  %s\nsecond: %s", first.WgConfig, second.WgConfig)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -run TestHandleAPIConfig -v`
Expected: FAIL — `got.WgConfig` doesn't exist yet (`DesktopConfig` has no such field), build error.

- [ ] **Step 3: Add the field and wire the handler**

In `api.go`, add to `DesktopConfig`:

```go
type DesktopConfig struct {
	HubURLs             []string `json:"hubUrls"`
	HubPin              string   `json:"hubPin"`
	HubToken            string   `json:"hubToken"`
	Peer                string   `json:"peer"`
	ObfProfile          string   `json:"obfProfile"`
	ObfKey              string   `json:"obfKey"`
	Streams             int      `json:"streams"`
	SplitMode           string   `json:"splitMode"`
	XraySubscriptionURL string   `json:"xraySubscriptionUrl"`
	WgConfig            string   `json:"wgConfig,omitempty"`
}
```

Replace `handleAPIConfig` with (note the lock now spans the whole function via `defer`, and `"log"` is a new import for this file):

```go
func handleAPIConfig(cfg *Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		username := bearerUser(cfg, r)
		if username == "" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		usersMu.Lock()
		defer usersMu.Unlock()
		users, _ := loadUsers(cfg.UsersFile)
		u := findUser(users, username)
		if u == nil {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		isAndroid := r.URL.Query().Get("device") == "android"
		accounts, streams := u.DesktopAccounts, u.DesktopStreams
		if isAndroid {
			accounts, streams = u.AndroidAccounts, u.AndroidStreams
		}
		if len(accounts) == 0 {
			w.WriteHeader(http.StatusNotFound)
			return
		}

		var wgConfig string
		if isAndroid {
			priv, err := ensureAndroidWGPeer(cfg, users, u)
			if err != nil {
				log.Printf("config: %s: android wg: %v", username, err)
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			if err := saveUsers(cfg.UsersFile, users); err != nil {
				log.Printf("config: %s: save android wg: %v", username, err)
			}
			wgConfig, err = buildAndroidWGConf(cfg, priv, u.AndroidWGIP, cfg.WGExcludeCIDRs)
			if err != nil {
				log.Printf("config: %s: build android wg conf: %v", username, err)
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
		}

		urls := make([]string, 0, len(accounts))
		for _, port := range accounts {
			urls = append(urls, fmt.Sprintf("https://%s:%d/turn-creds", cfg.HubHost, port))
		}
		if streams == 0 {
			streams = 10
		}
		peerAddr := cfg.PeerAddress
		if !isAndroid && cfg.DesktopPeer != "" {
			peerAddr = cfg.DesktopPeer
		}
		out := DesktopConfig{
			HubURLs: urls, HubPin: cfg.HubPin, HubToken: cfg.HubToken,
			Peer: peerAddr, ObfProfile: "rtpopus3", ObfKey: cfg.ObfKeyHex,
			Streams: streams, SplitMode: u.SplitMode, WgConfig: wgConfig,
		}
		if u.XraySubID != "" {
			out.XraySubscriptionURL = fmt.Sprintf("https://%s:2096/sub/%s", cfg.HubHost, u.XraySubID)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	}
}
```

Add `"log"` to `api.go`'s import block (currently `encoding/json`, `fmt`, `net/http`, `strings`, `time`, `golang.org/x/crypto/bcrypt`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/lev/vkturn-ios-portal && go test ./... -v`
Expected: all PASS — every pre-existing test plus every test added in Tasks 1-6.

- [ ] **Step 5: `gofmt` and full local test run**

Run: `cd /home/lev/vkturn-ios-portal && gofmt -l . && go build ./... && go test ./...`
Expected: `gofmt -l .` prints nothing (clean), build succeeds, all tests pass.

---

## Task 7: `cliDelUser` teardown + deploy portal to `vps` + live verification

**Files:**
- Modify: `/home/lev/vkturn-ios-portal/main.go` (`cliDelUser`)

No unit test for this step — `cliDelUser` (like `cliAddUser`/`cliAddAndroidUser`/etc.) has no existing test coverage in this repo (all CLI mutation commands are verified live, not unit tested — same established convention this plan follows for `wg`-shelling code without the `wgSetPeer` seam). Verified via the live checks below instead.

- [ ] **Step 1: Add android teardown to `cliDelUser`**

In `main.go`'s `cliDelUser`, right after the existing `if u.WGPubkey != ""` block:

```go
	if u.WGPubkey != "" {
		if err := removePeer(cfg, username, u.WGPubkey); err != nil {
			return fmt.Errorf("remove wg peer: %w", err)
		}
	}
	if u.AndroidWGPubkey != "" {
		_ = exec.Command("wg", "set", cfg.WGIface, "peer", u.AndroidWGPubkey, "remove").Run()
		if err := rewritePeerBlock(cfg, "ios-portal-android", username, "", ""); err != nil {
			return fmt.Errorf("remove android wg peer: %w", err)
		}
	}
```

- [ ] **Step 2: `gofmt` + build one more time**

Run: `cd /home/lev/vkturn-ios-portal && gofmt -l . && go build -o /tmp/vkturn-ios-portal-new ./...`
Expected: clean gofmt, build succeeds.

- [ ] **Step 3: Deploy to `vps`**

```bash
ssh vps "sudo cp /usr/local/bin/vkturn-ios-portal /usr/local/bin/vkturn-ios-portal.bak-$(date +%Y%m%d-%H%M%S)"
scp /tmp/vkturn-ios-portal-new vps:/tmp/vkturn-ios-portal-new
ssh vps "sudo cp /tmp/vkturn-ios-portal-new /usr/local/bin/vkturn-ios-portal && sudo systemctl restart vkturn-ios-portal && sleep 1 && systemctl is-active vkturn-ios-portal"
```

Also add the exclude-CIDR values to the live config before restart, so `WGExcludeCIDRs` isn't empty in production:

```bash
ssh vps "sudo python3 -c \"
import json
p = '/etc/vkturn/ios-portal/config.json'
d = json.load(open(p))
d['wg_exclude_cidrs'] = ['95.163.0.0/16', '90.156.0.0/16']
json.dump(d, open(p, 'w'), indent=2)
\""
```

(Do this BEFORE the restart above, or restart again after — order doesn't matter as long as both land before the live verification in Step 4.)

- [ ] **Step 4: Live verification on `vps`, throwaway account**

Create a throwaway android-login test account, verify idempotency + self-heal + exclude-CIDR + concurrency, then tear it down — mirrors exactly the process already used this session for the `androidlogin` panel.js feature:

```bash
ssh vps "sudo vkturn-ios-portal addandroid wg_test_verify 8445 8"
```

```bash
ssh vps '
TOKEN=$(curl -sk -X POST https://127.0.0.1:8449/api/v1/login -H "Content-Type: application/json" -d "{\"username\":\"wg_test_verify\",\"password\":\"<password from addandroid output above>\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)[\"token\"])")
curl -sk "https://127.0.0.1:8449/api/v1/config?device=android" -H "Authorization: Bearer $TOKEN" -o /tmp/cfg1.json
curl -sk "https://127.0.0.1:8449/api/v1/config?device=android" -H "Authorization: Bearer $TOKEN" -o /tmp/cfg2.json
python3 -c "
import json
a = json.load(open(\"/tmp/cfg1.json\"))[\"wgConfig\"]
b = json.load(open(\"/tmp/cfg2.json\"))[\"wgConfig\"]
assert a == b, \"wgConfig changed between calls\"
assert \"95.163.\" not in a, \"exclude CIDR leaked into AllowedIPs\"
assert \"90.156.\" not in a, \"exclude CIDR leaked into AllowedIPs\"
print(\"OK - idempotent, exclude-CIDR respected\")
"
rm -f /tmp/cfg1.json /tmp/cfg2.json
'
```

Concurrency check (two DIFFERENT test accounts fetched at the same time, confirming `wgcl.conf` doesn't lose either block):

```bash
ssh vps "sudo vkturn-ios-portal addandroid wg_test_verify2 8446 8"
```

```bash
ssh vps '
T1=$(curl -sk -X POST https://127.0.0.1:8449/api/v1/login -H "Content-Type: application/json" -d "{\"username\":\"wg_test_verify\",\"password\":\"<...>\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)[\"token\"])")
T2=$(curl -sk -X POST https://127.0.0.1:8449/api/v1/login -H "Content-Type: application/json" -d "{\"username\":\"wg_test_verify2\",\"password\":\"<...>\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)[\"token\"])")
curl -sk "https://127.0.0.1:8449/api/v1/config?device=android" -H "Authorization: Bearer $T1" -o /dev/null &
curl -sk "https://127.0.0.1:8449/api/v1/config?device=android" -H "Authorization: Bearer $T2" -o /dev/null &
wait
sudo grep -c "ios-portal-android" /etc/vkturn/ios-portal/wgcl.conf
'
```

Expected: prints `2` — exactly one `# ios-portal-android:<username>` marker line per peer block (see `rewritePeerBlock`'s `newBlock` format in Task 2), one block per test account. Both peers should also show up in `sudo wg show wgcl peers`.

- [ ] **Step 5: Tear down both test accounts**

```bash
ssh vps "sudo vkturn-ios-portal deluser wg_test_verify && sudo vkturn-ios-portal deluser wg_test_verify2"
ssh vps "sudo wg show wgcl peers"
```

Expected: neither test pubkey appears in the peer list anymore.

---

## Task 8: `panel.js` — drop WG from `androidlogin`'s memo

**Files:**
- Modify: `/home/vkturn/vkturn-panel/panel.js` (deploy target on `ai-lan`) via a local staged copy, same recipe as the `androidlogin` feature earlier this session.

- [ ] **Step 1: Pull the current live file locally**

```bash
ssh ai-lan "cat /home/vkturn/vkturn-panel/panel.js" > /tmp/panel.js.snapshot
cp /tmp/panel.js.snapshot /tmp/panel.js.new
```

- [ ] **Step 2: Edit `/tmp/panel.js.new`**

Change `genAndroidLoginArtifact` to stop calling `buildAndroidWgPeer` and stop persisting WG fields — it now only manages the portal login (accounts/streams), matching `genDesktopArtifact`'s shape exactly:

```js
function genAndroidLoginArtifact(prof, profiles) {
  const ssh = S.wg && S.wg.serverSsh; if (!ssh) throw new Error('wg.serverSsh не задан');
  const port = String((S.wg && S.wg.serverSshPort) || 22);
  const accountsCsv = prof.accounts.join(',');
  const streams = prof.streams || 10;
  const shq = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;
  if (prof.androidLoginUsername) {
    const remoteCmd = ['vkturn-ios-portal', 'editandroid', prof.androidLoginUsername, accountsCsv, String(streams)]
      .map(shq).join(' ');
    try {
      execFileSync('ssh', ['-p', port, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', ssh, remoteCmd], { timeout: 20000 });
    } catch (e) {
      throw new Error('editandroid не прошёл: ' + ((e.stderr && e.stderr.toString().trim()) || e.message));
    }
    const file = path.join(OUTBOX, prof.artifact);
    fs.writeFileSync(file, androidLoginMemo(prof.name, prof.androidLoginUsername, null));
    return { file, filename: prof.filename, mime: 'text/plain', androidLoginUsername: prof.androidLoginUsername,
             note: `android-портал, логин ${prof.androidLoginUsername}, аккаунты ${accountsCsv}` };
  }
  let username = slugify(prof.name);
  let out;
  try {
    const remoteCmd = ['vkturn-ios-portal', 'addandroid', username, accountsCsv, String(streams)].map(shq).join(' ');
    out = execFileSync('ssh', ['-p', port, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', ssh, remoteCmd], { timeout: 20000 }).toString();
  } catch (e) {
    if (!portalUsernameCollision(e)) throw new Error('addandroid не прошёл: ' + ((e.stderr && e.stderr.toString().trim()) || e.message));
    username += '_android';
    try {
      const remoteCmd = ['vkturn-ios-portal', 'addandroid', username, accountsCsv, String(streams)].map(shq).join(' ');
      out = execFileSync('ssh', ['-p', port, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', ssh, remoteCmd], { timeout: 20000 }).toString();
    } catch (e2) {
      throw new Error('addandroid не прошёл: ' + ((e2.stderr && e2.stderr.toString().trim()) || e2.message));
    }
  }
  const password = (out.match(/password:\s*(\S+)/) || [])[1];
  if (!password) throw new Error('не разобрал пароль из вывода addandroid:\n' + out);
  const text = androidLoginMemo(prof.name, username, password);
  const file = path.join(OUTBOX, `${prof.id}.txt`);
  fs.writeFileSync(file, text);
  return { file, filename: `vkturn-android-${username}.txt`, mime: 'text/plain', androidLoginUsername: username,
           note: `android-портал, логин ${username}, аккаунты ${accountsCsv}` };
}

function androidLoginMemo(name, username, password) {
  const lines = [`VK-TURN — доступ для Android, портал-логин (${name})`, ''];
  if (password) {
    lines.push('1. Установи приложение, на экране "Добавить сервер" выбери "Вход по паролю портала".',
      `   Логин: ${username}`, `   Пароль: ${password}`, '',
      '2. Войди — WireGuard настроится сам, ничего вставлять не нужно.');
  } else {
    lines.push(`Логин на портале (не менялся): ${username}`, '',
      'WireGuard настраивается автоматически при входе.');
  }
  return lines.join('\n') + '\n';
}
```

Note the second parameter `profiles` on `genAndroidLoginArtifact` is now unused (no more `buildAndroidWgPeer(prof, profiles)` call) — leave the parameter in place since `genArtifact`'s dispatcher still calls it as `genAndroidLoginArtifact(prof, profiles)` alongside the other artifact functions that do use their second argument; removing it would be a needless signature mismatch against the dispatcher for no benefit.

Remove the `🔑` peerSnip link's `androidlogin` branch (panel no longer owns that WG peer, so `device.peerSnip` will never be set for this type anymore):

```js
      ${d.type === 'android' ? ` <a href="/peer/${person.id}/${d.type}">🔑</a>` : ''}
```

(revert this one line back to checking only `'android'`, undoing the `|| d.type === 'androidlogin'` addition from earlier this session)

Update the help paragraph under the create-form to drop the now-inaccurate "WG-конфиг из памятки вставляет вручную" line:

```js
<p style="color:#888">Артефакты в <code>outbox/</code>. Android (.fabackup): родня ставит APK → Импорт файла → пароль, всё в одном файле. Android (портал-логин): родня вводит логин/пароль в приложении сама, WireGuard настраивается автоматически при входе. Оба способа требуют поднятого WG-сервера (см. RUVDS-wg-egress-setup.md). «+ платформа» у уже заведённого человека добавляет ещё одно устройство на том же имени/аккаунтах — стримы/сплит потом донастраиваются через ✎.</p>
```

- [ ] **Step 3: Syntax-check and diff-review**

```bash
cp /tmp/panel.js.new /tmp/panel-check.js && node -c /tmp/panel-check.js && echo "syntax OK"
ssh ai-lan "diff -u /home/vkturn/vkturn-panel/panel.js /tmp/panel.js.new" || true
```

Expected: syntax OK; diff shows only the `genAndroidLoginArtifact`/`androidLoginMemo` simplification, the reverted 🔑-link condition, and the help-text line — nothing else.

- [ ] **Step 4: Deploy**

```bash
ssh ai-lan "sudo cp /home/vkturn/vkturn-panel/panel.js /home/vkturn/vkturn-panel/panel.js.bak-$(date +%Y%m%d-%H%M%S)-wgautoconfig"
scp /tmp/panel.js.new ai-lan:/tmp/panel.js.new
ssh ai-lan "sudo cp /tmp/panel.js.new /home/vkturn/vkturn-panel/panel.js && sudo chown vkturn:vkturn /home/vkturn/vkturn-panel/panel.js && sudo systemctl restart vkturn-panel && sleep 1 && systemctl is-active vkturn-panel && curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8088/"
```

Expected: `active`, `200`.

- [ ] **Step 5: Live verification — throwaway `androidlogin` profile**

```bash
ssh ai-lan "curl -s -X POST http://127.0.0.1:8088/create --data 'name=панель-тест-wgauto&device=androidlogin&streams=8&account=8445' -D - -o /dev/null"
ssh ai-lan "cat /home/vkturn/vkturn-panel/outbox/\$(python3 -c \"
import json
d = json.load(open('/home/vkturn/vkturn-panel/panel-profiles.json'))
p = [x for x in d if x['name']=='панель-тест-wgauto'][0]
print(p['devices'][0]['artifact'])
\")"
```

Expected: memo file contains login/password and "WireGuard настроится сам" — no `[Interface]`/`[Peer]` block, no private key.

- [ ] **Step 6: Tear down the test profile**

```bash
ssh ai-lan "python3 -c \"
import json
d = json.load(open('/home/vkturn/vkturn-panel/panel-profiles.json'))
pid = [x for x in d if x['name']=='панель-тест-wgauto'][0]['id']
print(pid)
\""
```

Then, using the printed `<id>`:

```bash
ssh ai-lan "curl -s -X POST http://127.0.0.1:8088/delete/<id>/androidlogin -D - -o /dev/null"
ssh ai-lan "curl -s -X POST http://127.0.0.1:8088/delete-person/<id> -D - -o /dev/null"
```

Confirm the portal-side user is gone too (deluser runs as part of `/delete`):

```bash
ssh vps "sudo python3 -c \"
import json
d = json.load(open('/etc/vkturn/ios-portal/users.json'))
print('found' if any(u['username']=='panel_test_wgauto' for u in d) else 'gone')
\""
```

Expected: `gone`.

---

## Task 9: Android — consume `wgConfig`

**Files:**
- Modify: `turn-proxy-android/app/src/main/java/com/freeturn/app/domain/portal/PortalApiClient.kt`
- Modify: `turn-proxy-android/app/src/main/java/com/freeturn/app/viewmodel/settings/SettingsViewModel.kt`

No unit test — neither `PortalApiClient` nor `SettingsViewModel` has any existing test coverage in this repo (`app/src/test` only covers `data/share`, `domain/proxy`, `domain/update`), and this is a two-field, mechanically-verified-by-the-Go-side-tests change. Verified by Task 10's mandatory live device check instead.

- [ ] **Step 1: Add `wgConfig` to `PortalConfig`**

In `PortalApiClient.kt`, extend the data class and its parsing:

```kotlin
data class PortalConfig(
    val hubUrls: List<String>,
    val hubPin: String,
    val hubToken: String,
    val peer: String,
    val obfProfile: String,
    val obfKey: String,
    val streams: Int,
    val wgConfig: String
)
```

```kotlin
            PortalConfig(
                hubUrls = urls?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty(),
                hubPin = json.optString("hubPin"),
                hubToken = json.optString("hubToken"),
                peer = json.optString("peer"),
                obfProfile = json.optString("obfProfile"),
                obfKey = json.optString("obfKey"),
                streams = json.optInt("streams", DEFAULT_STREAMS),
                wgConfig = json.optString("wgConfig")
            )
```

- [ ] **Step 2: Consume it in `loginToPortal`**

In `SettingsViewModel.kt`, extend the `ClientConfig(...)` construction inside `loginToPortal`:

```kotlin
                val server = Server(
                    name = "VK-TURN ($username)",
                    client = ClientConfig(
                        provider = Provider.HUB,
                        serverAddress = cfg.peer,
                        hubUrl = cfg.hubUrls.joinToString(","),
                        hubPin = cfg.hubPin,
                        hubToken = cfg.hubToken,
                        threads = cfg.streams.takeIf { it > 0 } ?: ClientConfig.DEFAULT_THREADS,
                        tcpForward = true,
                        bond = true,
                        tunnelTransport = if (cfg.wgConfig.isNotBlank()) TunnelTransport.WIREGUARD else TunnelTransport.NONE,
                        wireGuardConfig = cfg.wgConfig
                    ),
                    opts = ServerOpts(
                        obfProfile = cfg.obfProfile.ifBlank { ObfProfile.NONE },
                        obfKey = cfg.obfKey
                    )
                )
```

Add the import for `TunnelTransport` if `SettingsViewModel.kt` doesn't already have it (check the existing import block first — `ClientConfig.kt` defines `TunnelTransport` in the same `com.freeturn.app.data.config` package `SettingsViewModel.kt` already imports `ClientConfig`/`Provider`/`ObfProfile` from, so it's likely just `import com.freeturn.app.data.config.TunnelTransport` if not already covered by an existing wildcard/package import).

- [ ] **Step 3: Build**

```bash
cd /home/lev/turn-proxy-android && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /home/lev/turn-proxy-android
git add app/src/main/java/com/freeturn/app/domain/portal/PortalApiClient.kt app/src/main/java/com/freeturn/app/viewmodel/settings/SettingsViewModel.kt
git commit -m "feat(portal): auto-configure WireGuard from portal login, no manual paste"
```

---

## Task 10: Mandatory live phone verification (hard gate)

**Files:** none — this is a manual verification task, not a code change.

- [ ] **Step 1: Build and install a debug APK on the user's phone**

```bash
cd /home/lev/turn-proxy-android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Create a real (or throwaway) android-login account via the panel**, get its login/password from the memo (Task 8's flow).

- [ ] **Step 3: On the phone** — open the app, "Добавить сервер" → "Вход по паролю портала" (`PortalLoginDialog`), enter the login/password. Confirm:
  - Login succeeds (no error dialog).
  - WireGuard comes up **without ever visiting `WireGuardConfigCard`**.
  - Real traffic flows (open a page/app that needs the tunnel, confirm it loads; check `ip route`/the app's own traffic stats if available).

- [ ] **Step 4: If this is a throwaway test account**, tear it down the same way as Task 8 Step 6, and uninstall the debug APK if it shouldn't linger on the phone.

- [ ] **Step 5: Only after Step 3 passes**, treat Tasks 6-9's changes as ready for family rollout. Per the spec's rollout note, actual family accounts still go out in the next batch release, not per-fix.
