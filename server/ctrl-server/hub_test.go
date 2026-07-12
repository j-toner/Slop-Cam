package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func newTestServer(t *testing.T) (*Hub, *httptest.Server) {
	hub := newHub(t.TempDir())
	go hub.run()
	srv := httptest.NewServer(wsHandler(hub))
	t.Cleanup(srv.Close)
	return hub, srv
}

func wsConnect(t *testing.T, srv *httptest.Server, roleQuery string) *websocket.Conn {
	ctx := context.Background()
	url := "ws" + strings.TrimPrefix(srv.URL, "http") + "/ws?role=" + roleQuery
	conn, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		t.Fatalf("dial %s: %v", roleQuery, err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	return conn
}

func readMsg(t *testing.T, conn *websocket.Conn) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return string(data)
}

func TestFlashlightRouting(t *testing.T) {
	_, srv := newTestServer(t)

	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	ctx := context.Background()
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:FLASHLIGHT_ON"))

	msg := readMsg(t, cam)
	if msg != "CMD:FLASHLIGHT_ON" {
		t.Errorf("got %q, want CMD:FLASHLIGHT_ON", msg)
	}
}

func TestStartStreamOnViewerConnect(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	time.Sleep(20 * time.Millisecond)

	_ = wsConnect(t, srv, "viewer")

	msg := readMsg(t, cam)
	if !strings.HasPrefix(msg, "CMD:START_STREAM") {
		t.Errorf("got %q, want CMD:START_STREAM prefix", msg)
	}
}

func TestStartStreamUsesViewerResolution(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	time.Sleep(20 * time.Millisecond)

	_ = wsConnect(t, srv, "viewer&res=1080p")

	msg := readMsg(t, cam)
	if msg != "CMD:START_STREAM:1080p" {
		t.Errorf("got %q, want CMD:START_STREAM:1080p", msg)
	}
}

func TestCamReconnectWithViewersWaiting(t *testing.T) {
	_, srv := newTestServer(t)
	_ = wsConnect(t, srv, "viewer")
	time.Sleep(20 * time.Millisecond)

	cam := wsConnect(t, srv, "cam")

	msg := readMsg(t, cam)
	if !strings.HasPrefix(msg, "CMD:START_STREAM") {
		t.Errorf("got %q, want CMD:START_STREAM prefix", msg)
	}
}

func TestSnapshotNotifyIncludesDayDir(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam)    // drain START_STREAM
	readMsg(t, viewer) // drain EVENT:MOTION_REC state sync

	cam.Write(context.Background(), websocket.MessageBinary, []byte("fakejpeg"))

	msg := readMsg(t, viewer)
	day := time.Now().Format("2006-01-02")
	if !strings.HasPrefix(msg, "SNAPSHOT:/snapshots/"+day+"/") {
		t.Errorf("got %q, want SNAPSHOT:/snapshots/%s/... prefix", msg, day)
	}
}

func TestCrossOriginBrowserRejected(t *testing.T) {
	_, srv := newTestServer(t)
	ctx := context.Background()
	url := "ws" + strings.TrimPrefix(srv.URL, "http") + "/ws?role=viewer"
	_, resp, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Origin": []string{"http://evil.example"}},
	})
	if err == nil {
		t.Fatal("expected cross-origin dial to fail")
	}
	if resp != nil && resp.StatusCode == http.StatusSwitchingProtocols {
		t.Errorf("handshake succeeded despite foreign Origin")
	}
}

func expectNoMsg(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	_, data, err := conn.Read(ctx)
	if err == nil {
		t.Errorf("expected no message, got %q", string(data))
	}
}

func TestSecurityModeKeepsStreamWithoutViewers(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM from viewer connect

	ctx := context.Background()
	// turning security on while already streaming must NOT restart the
	// stream (that freeze was the old bug) — no second START_STREAM.
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_ON:1:480p"))
	expectNoMsg(t, cam)

	viewer.Close(websocket.StatusNormalClosure, "bye")
	expectNoMsg(t, cam) // no STOP_STREAM while security mode is on
}

func TestSecurityOnNoRedundantStartWhileStreaming(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM from viewer connect

	// a re-sync of security mode (e.g. viewer reconnect) must not restart
	// the already-running stream
	for i := 0; i < 3; i++ {
		viewer.Write(context.Background(), websocket.MessageText,
			[]byte("CMD:SECURITY_ON:1:480p"))
		expectNoMsg(t, cam)
	}
}

func TestSecurityOffStopsStreamWithoutViewers(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM (viewer connect)

	// drop the viewer and the only viewer; stream stops
	viewer.Close(websocket.StatusNormalClosure, "bye")
	readMsg(t, cam) // drain STOP_STREAM

	// a fresh viewer comes back, turns security on (no redundant start),
	// then off; with no viewers left the stream stops again
	viewer2 := wsConnect(t, srv, "viewer")
	readMsg(t, cam) // drain START_STREAM (viewer2 connect)
	ctx := context.Background()
	viewer2.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_ON:1:480p"))
	viewer2.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_OFF"))
	viewer2.Close(websocket.StatusNormalClosure, "bye")

	if msg := readMsg(t, cam); msg != "CMD:STOP_STREAM" {
		t.Errorf("got %q, want CMD:STOP_STREAM", msg)
	}
}

func TestMotionRecIndependentOfSnapshots(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	// motion recording on must make the cam detect motion (detect=1) even
	// with snapshots off (snap=0) — the two are independent.
	viewer.Write(context.Background(), websocket.MessageText,
		[]byte("CMD:MOTION_REC_ON:1:480p"))
	if msg := readMsg(t, cam); msg != "CMD:MOTION:snap=0:detect=1" {
		t.Errorf("got %q, want CMD:MOTION:snap=0:detect=1", msg)
	}
}

func TestMotionRecStartsAndStopsStream(t *testing.T) {
	motionIdleDuration = 100 * time.Millisecond
	motionSpinupGrace = 0
	defer func() {
		motionIdleDuration = 10 * time.Second
		motionSpinupGrace = 5 * time.Second
	}()
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	time.Sleep(50 * time.Millisecond)
	ctx := context.Background()

	// enable motion recording via a viewer, then drop the viewer so the
	// stream is free to be driven purely by motion
	viewer := wsConnect(t, srv, "viewer")
	readMsg(t, cam) // drain START_STREAM (viewer connect)
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:MOTION_REC_ON:1:480p"))
	readMsg(t, cam) // drain CMD:MOTION:snap=0:detect=1
	viewer.Close(websocket.StatusNormalClosure, "bye")
	readMsg(t, cam) // drain STOP_STREAM from the viewer leaving

	// first motion event switches the recorder on and starts the stream
	cam.Write(ctx, websocket.MessageText, []byte("EVENT:MOTION"))
	if msg := readMsg(t, cam); msg != "CMD:START_STREAM:720p" {
		t.Errorf("got %q, want CMD:START_STREAM:720p", msg)
	}

	// after the idle timeout with no further motion, motion recording stops
	// and — with no viewer — the stream is stopped too
	if msg := readMsg(t, cam); msg != "CMD:STOP_STREAM" {
		t.Errorf("got %q, want CMD:STOP_STREAM", msg)
	}
}

// A motion event that has to cold-start the stream must not have the cam's
// publish spin-up counted against the recording window: the first idle timer
// is armed with motionIdleDuration + motionSpinupGrace, otherwise a single
// motion event yields a clip that starts late and lasts only a few seconds.
func TestMotionColdStartExtendsIdleWindow(t *testing.T) {
	motionIdleDuration = 100 * time.Millisecond
	motionSpinupGrace = 500 * time.Millisecond
	defer func() {
		motionIdleDuration = 10 * time.Second
		motionSpinupGrace = 5 * time.Second
	}()
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	time.Sleep(50 * time.Millisecond)
	ctx := context.Background()

	viewer := wsConnect(t, srv, "viewer")
	readMsg(t, cam) // drain START_STREAM (viewer connect)
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:MOTION_REC_ON:1:480p"))
	readMsg(t, cam) // drain CMD:MOTION:snap=0:detect=1
	viewer.Close(websocket.StatusNormalClosure, "bye")
	readMsg(t, cam) // drain STOP_STREAM from the viewer leaving

	// motion while idle: stream cold-starts...
	start := time.Now()
	cam.Write(ctx, websocket.MessageText, []byte("EVENT:MOTION"))
	if msg := readMsg(t, cam); msg != "CMD:START_STREAM:720p" {
		t.Errorf("got %q, want CMD:START_STREAM:720p", msg)
	}

	// ...and the stop must come after idle+grace (600ms), not after the bare
	// idle duration (100ms) — the spin-up grace protects the recording window
	if msg := readMsg(t, cam); msg != "CMD:STOP_STREAM" {
		t.Errorf("got %q, want CMD:STOP_STREAM", msg)
	}
	if elapsed := time.Since(start); elapsed < 550*time.Millisecond {
		t.Errorf("motion recording stopped after %v, want >= idle+grace (600ms)", elapsed)
	}
}

func TestSecurityOnStartsStreamWhenCamRegisters(t *testing.T) {
	_, srv := newTestServer(t)
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(20 * time.Millisecond)
	viewer.Write(context.Background(), websocket.MessageText,
		[]byte("CMD:SECURITY_ON:2:360p"))
	time.Sleep(50 * time.Millisecond)
	viewer.Close(websocket.StatusNormalClosure, "bye")
	time.Sleep(50 * time.Millisecond)

	cam := wsConnect(t, srv, "cam")
	msg := readMsg(t, cam)
	if !strings.HasPrefix(msg, "CMD:START_STREAM") {
		t.Errorf("got %q, want CMD:START_STREAM prefix", msg)
	}
}

func TestInvalidSecurityCmdIgnored(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	// bogus fps/res must not reach the cam or flip security mode
	viewer.Write(context.Background(), websocket.MessageText,
		[]byte("CMD:SECURITY_ON:99:$(rm -rf /)"))
	expectNoMsg(t, cam)
}

func TestStopStreamOnViewerDisconnect(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)

	readMsg(t, cam) // drain START_STREAM

	viewer.Close(websocket.StatusNormalClosure, "bye")

	msg := readMsg(t, cam)
	if msg != "CMD:STOP_STREAM" {
		t.Errorf("got %q, want CMD:STOP_STREAM", msg)
	}
}

func TestCamReconnectRestartsStream(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	_ = wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	// cam drops and reconnects; the new cam must get a fresh START_STREAM
	// (the stream was marked not-streaming on disconnect)
	cam.Close(websocket.StatusNormalClosure, "bye")
	time.Sleep(50 * time.Millisecond)
	cam = wsConnect(t, srv, "cam")
	if msg := readMsg(t, cam); !strings.HasPrefix(msg, "CMD:START_STREAM") {
		t.Errorf("got %q, want CMD:START_STREAM prefix", msg)
	}
}

func TestStaleCamKickStillGetsStartStream(t *testing.T) {
	_, srv := newTestServer(t)
	oldCam := wsConnect(t, srv, "cam")
	_ = wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, oldCam) // drain START_STREAM sent to the old cam

	// the cam app restarts and reconnects while its old connection is
	// still half-open: the hub kicks the stale cam, and the replacement
	// must still be told to stream — the register path used to leave
	// streaming=true, so the fresh cam never got a START_STREAM
	newCam := wsConnect(t, srv, "cam")
	time.Sleep(100 * time.Millisecond)

	if msg := readMsg(t, newCam); !strings.HasPrefix(msg, "CMD:START_STREAM") {
		t.Errorf("got %q, want CMD:START_STREAM prefix", msg)
	}
}

// fakeRecorder observes recorder transitions so tests can assert the hub
// never restarts or stops ffmpeg at the wrong moment.
type fakeRecorder struct {
	mu     sync.Mutex
	starts []string // "fps:res" per Start call
	stops  int
}

func (f *fakeRecorder) Start(fps, res string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.starts = append(f.starts, fps+":"+res)
}

func (f *fakeRecorder) Stop() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.stops++
}

func (f *fakeRecorder) counts() ([]string, int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.starts...), f.stops
}

func newTestServerWithRecorder(t *testing.T) (*Hub, *httptest.Server, *fakeRecorder) {
	hub := newHub(t.TempDir())
	rec := &fakeRecorder{}
	hub.recorder = rec
	go hub.run()
	srv := httptest.NewServer(wsHandler(hub))
	t.Cleanup(srv.Close)
	return hub, srv, rec
}

// GO-1: a motion event while security mode is already recording must not
// restart ffmpeg (it SIGINTs the live segment and may switch params).
func TestMotionWhileSecurityOnDoesNotRestartRecorder(t *testing.T) {
	_, srv, rec := newTestServerWithRecorder(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	ctx := context.Background()
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_ON:1:480p"))
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:MOTION_REC_ON:1:480p"))
	readMsg(t, cam) // drain CMD:MOTION:snap=0:detect=1

	cam.Write(ctx, websocket.MessageText, []byte("EVENT:MOTION"))
	time.Sleep(100 * time.Millisecond)

	starts, stops := rec.counts()
	if len(starts) != 1 || starts[0] != "1:480p" {
		t.Errorf("recorder starts = %v, want exactly one 1:480p", starts)
	}
	if stops != 0 {
		t.Errorf("recorder stopped %d times, want 0", stops)
	}
}

// CG-4: turning security mode off while a motion window is active must not
// kill the in-flight motion recording.
func TestSecurityOffKeepsActiveMotionRecording(t *testing.T) {
	_, srv, rec := newTestServerWithRecorder(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	ctx := context.Background()
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:MOTION_REC_ON:1:480p"))
	readMsg(t, cam) // drain CMD:MOTION
	cam.Write(ctx, websocket.MessageText, []byte("EVENT:MOTION"))
	time.Sleep(50 * time.Millisecond) // motion window now active (10s default)

	viewer.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_ON:1:480p"))
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_OFF"))
	time.Sleep(50 * time.Millisecond)

	if _, stops := rec.counts(); stops != 0 {
		t.Errorf("recorder stopped %d times during an active motion window, want 0", stops)
	}
}

// CG-5: changing motion-rec params while a motion window is active must
// apply them, not silently defer to the next motion cycle.
func TestMotionRecParamChangeAppliesWhileActive(t *testing.T) {
	_, srv, rec := newTestServerWithRecorder(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	ctx := context.Background()
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:MOTION_REC_ON:1:480p"))
	readMsg(t, cam) // drain CMD:MOTION
	cam.Write(ctx, websocket.MessageText, []byte("EVENT:MOTION"))
	time.Sleep(50 * time.Millisecond)

	viewer.Write(ctx, websocket.MessageText, []byte("CMD:MOTION_REC_ON:5:720p"))
	time.Sleep(50 * time.Millisecond)

	starts, _ := rec.counts()
	if len(starts) == 0 || starts[len(starts)-1] != "5:720p" {
		t.Errorf("recorder starts = %v, want last start 5:720p", starts)
	}
}

// CG-3: after the motion window expires under security mode, motionActive
// must clear — otherwise a later SECURITY_OFF leaves the stream running
// with nothing consuming it.
func TestSecurityOffAfterMotionExpiryStopsStream(t *testing.T) {
	motionIdleDuration = 100 * time.Millisecond
	motionSpinupGrace = 0
	defer func() {
		motionIdleDuration = 10 * time.Second
		motionSpinupGrace = 5 * time.Second
	}()
	_, srv, _ := newTestServerWithRecorder(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	ctx := context.Background()
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:MOTION_REC_ON:1:480p"))
	readMsg(t, cam) // drain CMD:MOTION
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_ON:1:480p"))
	cam.Write(ctx, websocket.MessageText, []byte("EVENT:MOTION"))
	time.Sleep(300 * time.Millisecond) // motion window expires under security mode

	viewer.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_OFF"))
	viewer.Close(websocket.StatusNormalClosure, "bye")

	if msg := readMsg(t, cam); msg != "CMD:STOP_STREAM" {
		t.Errorf("got %q, want CMD:STOP_STREAM", msg)
	}
}

// CG-2: an idle timer that already fired but lost the race to a fresh
// motion event (time.Timer.Stop returned false, expiry blocked on the
// mutex) must not stop the recorder the new event just re-armed.
func TestStaleMotionExpiryIgnored(t *testing.T) {
	h := newHub(t.TempDir())
	rec := &fakeRecorder{}
	h.recorder = rec
	h.cam = &Client{send: make(chan outMsg, 8)}
	h.motionRec = true
	h.streaming = true

	h.onMotionEvent() // arms the idle timer (gen 1), starts the recorder
	h.onMotionEvent() // fresh motion re-arms (gen 2)

	h.motionExpired(1) // gen-1 timer firing late, after the re-arm

	h.mu.RLock()
	active := h.motionActive
	h.mu.RUnlock()
	if !active {
		t.Error("stale expiry cleared motionActive despite fresh motion")
	}
	if _, stops := rec.counts(); stops != 0 {
		t.Errorf("stale expiry stopped the recorder %d times, want 0", stops)
	}
}

// GO-2/CG-10: dotfiles (.security.json) and directory indexes must not be
// readable or deletable through the file server — only real media files.
func TestFilesWithDeleteHidesDotfilesAndIndexes(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, ".security.json"), []byte("{}"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "cam_x.mp4"), []byte("vid"), 0644); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.StripPrefix("/recordings/", filesWithDelete(dir)))
	t.Cleanup(srv.Close)

	for _, tc := range []struct {
		method, path string
		want         int
	}{
		{"GET", "/recordings/.security.json", http.StatusNotFound},
		{"DELETE", "/recordings/.security.json", http.StatusNotFound},
		{"GET", "/recordings/", http.StatusNotFound}, // no directory index
		{"GET", "/recordings/cam_x.mp4", http.StatusOK},
		{"DELETE", "/recordings/cam_x.mp4", http.StatusNoContent},
	} {
		req, _ := http.NewRequest(tc.method, srv.URL+tc.path, nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s: %v", tc.method, tc.path, err)
		}
		resp.Body.Close()
		if resp.StatusCode != tc.want {
			t.Errorf("%s %s = %d, want %d", tc.method, tc.path, resp.StatusCode, tc.want)
		}
	}
	if _, err := os.Stat(filepath.Join(dir, ".security.json")); err != nil {
		t.Error("DELETE removed .security.json despite 404")
	}
}

// CG-7: two snapshots in the same millisecond must not overwrite each other.
func TestSaveSnapshotNoSameMillisecondOverwrite(t *testing.T) {
	h := newHub(t.TempDir())
	paths := map[string]bool{}
	deadline := time.Now().Add(2 * time.Second)
	n := 0
	for time.Now().Before(deadline) && n < 50 {
		p, err := h.saveSnapshot([]byte("jpg"))
		if err != nil {
			t.Fatal(err)
		}
		if paths[p] {
			t.Fatalf("saveSnapshot reused path %s (overwrite)", p)
		}
		paths[p] = true
		n++
	}
	if len(paths) != n {
		t.Errorf("%d snapshots produced %d files", n, len(paths))
	}
}

// Viewers get told when motion recording actually starts and stops
// (EVENT:MOTION_REC:1/0), and a newly registered viewer receives the
// current state so its indicator is right after a reconnect.
func TestMotionRecStateBroadcastToViewers(t *testing.T) {
	motionIdleDuration = 150 * time.Millisecond
	motionSpinupGrace = 0
	defer func() {
		motionIdleDuration = 10 * time.Second
		motionSpinupGrace = 5 * time.Second
	}()
	_, srv, _ := newTestServerWithRecorder(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	// state sync on register: not recording yet
	if msg := readMsg(t, viewer); msg != "EVENT:MOTION_REC:0" {
		t.Fatalf("on register got %q, want EVENT:MOTION_REC:0", msg)
	}

	ctx := context.Background()
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:MOTION_REC_ON:1:480p"))
	readMsg(t, cam) // drain CMD:MOTION

	cam.Write(ctx, websocket.MessageText, []byte("EVENT:MOTION"))
	if msg := readMsg(t, viewer); msg != "EVENT:MOTION_REC:1" {
		t.Errorf("on motion got %q, want EVENT:MOTION_REC:1", msg)
	}
	readMsg(t, viewer) // the raw EVENT:MOTION broadcast follows

	// window expires -> recording off
	if msg := readMsg(t, viewer); msg != "EVENT:MOTION_REC:0" {
		t.Errorf("on expiry got %q, want EVENT:MOTION_REC:0", msg)
	}
}
