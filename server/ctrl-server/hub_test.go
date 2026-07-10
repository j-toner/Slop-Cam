package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
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
	readMsg(t, cam) // drain START_STREAM

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
