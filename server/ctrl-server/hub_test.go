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
	readMsg(t, cam) // drain START_STREAM

	ctx := context.Background()
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_ON:1:480p"))
	readMsg(t, cam) // drain the (idempotent) START_STREAM security mode re-sends

	viewer.Close(websocket.StatusNormalClosure, "bye")
	expectNoMsg(t, cam) // no STOP_STREAM while security mode is on
}

func TestSecurityOffStopsStreamWithoutViewers(t *testing.T) {
	_, srv := newTestServer(t)
	cam := wsConnect(t, srv, "cam")
	viewer := wsConnect(t, srv, "viewer")
	time.Sleep(50 * time.Millisecond)
	readMsg(t, cam) // drain START_STREAM

	ctx := context.Background()
	viewer.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_ON:1:480p"))
	readMsg(t, cam) // drain START_STREAM re-sent by security mode
	viewer.Close(websocket.StatusNormalClosure, "bye")
	time.Sleep(50 * time.Millisecond)

	viewer2 := wsConnect(t, srv, "viewer")
	readMsg(t, cam) // drain START_STREAM for viewer2
	viewer2.Write(ctx, websocket.MessageText, []byte("CMD:SECURITY_OFF"))
	time.Sleep(50 * time.Millisecond)
	viewer2.Close(websocket.StatusNormalClosure, "bye")

	if msg := readMsg(t, cam); msg != "CMD:STOP_STREAM" {
		t.Errorf("got %q, want CMD:STOP_STREAM", msg)
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
