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
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		serveWs(hub, conn, r.URL.Query().Get("role"))
	}))
	t.Cleanup(srv.Close)
	return hub, srv
}

func wsConnect(t *testing.T, srv *httptest.Server, role string) *websocket.Conn {
	ctx := context.Background()
	url := "ws" + strings.TrimPrefix(srv.URL, "http") + "/ws?role=" + role
	conn, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		t.Fatalf("dial %s: %v", role, err)
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
