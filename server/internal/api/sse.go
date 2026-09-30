package api

import (
	"fmt"
	"net/http"
	"sync"
	"time"

	"memosync/internal/store"
)

// hub：SSE 订阅者注册表。Broadcast 在每次变更提交后被调用（store 层钩子）。
type hub struct {
	mu   sync.Mutex
	subs map[chan struct{}]struct{}
}

func newHub() *hub {
	return &hub{subs: make(map[chan struct{}]struct{})}
}

func (h *hub) add() chan struct{} {
	ch := make(chan struct{}, 1) // 缓冲 1：广播非阻塞，订阅者处理后自然排空
	h.mu.Lock()
	defer h.mu.Unlock()
	h.subs[ch] = struct{}{}
	return ch
}

func (h *hub) remove(ch chan struct{}) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.subs, ch)
}

func (h *hub) broadcast() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs {
		select {
		case ch <- struct{}{}:
		default: // 已有待处理事件，跳过（客户端 pull 是幂等增量）
		}
	}
}

// handleEvents GET /api/v1/sync/events
// SSE 长连接：有家庭成员变更时立即下发 "event: change"，客户端据此立即 pull。
// 每 25s 心跳，防止中间代理断开空闲连接。
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request, _ *store.User) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeErr(w, http.StatusInternalServerError, "当前环境不支持 SSE")
		return
	}
	ch := s.hub.add()
	defer s.hub.remove(ch)

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no") // nginx 反代不缓冲
	w.WriteHeader(http.StatusOK)
	fmt.Fprint(w, "retry: 3000\n\n")
	flusher.Flush()

	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ping.C:
			if _, err := fmt.Fprint(w, "event: ping\ndata: {}\n\n"); err != nil {
				return
			}
			flusher.Flush()
		case <-ch:
			if _, err := fmt.Fprint(w, "event: change\ndata: {}\n\n"); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}
