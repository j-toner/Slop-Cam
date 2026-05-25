package main

import (
	"context"
	"log"

	"github.com/coder/websocket"
)

const maxMsgSize = 4 * 1024 * 1024 // 4MB for JPEG snapshots

type ClientRole string

const (
	RoleCam    ClientRole = "cam"
	RoleViewer ClientRole = "viewer"
)

type outMsg struct {
	msgType websocket.MessageType
	data    []byte
}

type Client struct {
	hub    *Hub
	conn   *websocket.Conn
	role   ClientRole
	send   chan outMsg
	ctx    context.Context
	cancel context.CancelFunc
}

func (c *Client) sendMsg(msgType websocket.MessageType, data []byte) {
	select {
	case c.send <- outMsg{msgType, data}:
	default:
		log.Printf("send buffer full for %s, dropping", c.role)
	}
}

func serveWs(hub *Hub, conn *websocket.Conn, roleStr string) {
	role := ClientRole(roleStr)
	if role != RoleCam && role != RoleViewer {
		conn.Close(websocket.StatusPolicyViolation, "invalid role")
		return
	}
	conn.SetReadLimit(maxMsgSize)
	ctx, cancel := context.WithCancel(context.Background())
	c := &Client{hub: hub, conn: conn, role: role,
		send: make(chan outMsg, 256), ctx: ctx, cancel: cancel}
	hub.register <- c
	go c.writePump()
	go c.readPump()
}

func (c *Client) readPump() {
	defer func() {
		c.cancel()
		c.hub.unregister <- c
	}()
	for {
		msgType, data, err := c.conn.Read(c.ctx)
		if err != nil {
			return
		}
		msg := Message{client: c, data: data, msgType: msgType}
		if c.role == RoleCam {
			c.hub.fromCam <- msg
		} else {
			c.hub.fromViewer <- msg
		}
	}
}

func (c *Client) writePump() {
	defer c.conn.CloseNow()
	for {
		select {
		case msg, ok := <-c.send:
			if !ok {
				c.conn.Close(websocket.StatusNormalClosure, "done")
				return
			}
			if err := c.conn.Write(c.ctx, msg.msgType, msg.data); err != nil {
				return
			}
		case <-c.ctx.Done():
			return
		}
	}
}
