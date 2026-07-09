package main

import (
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

// Whitelists shared with the hub's command validation — recorder args are
// exec'd (no shell), but only known-good values reach ffmpeg anyway.
var allowedFps = map[string]bool{"0.5": true, "1": true, "2": true, "5": true}
var allowedRes = map[string]string{"360p": "360", "480p": "480", "720p": "720"}

// Recorder supervises an ffmpeg process that pulls the RTSP stream and
// re-encodes it into small low-fps H.265 segments for security-camera mode.
type Recorder struct {
	rtspURL string
	dir     string
	crf     string

	mu     sync.Mutex
	active bool
	gen    int // bumped on every Start/Stop to invalidate old supervise loops
	cmd    *exec.Cmd
}

func newRecorder(rtspURL, dir, crf string) *Recorder {
	return &Recorder{rtspURL: rtspURL, dir: dir, crf: crf}
}

func (r *Recorder) Start(fps, res string) {
	height := allowedRes[res]
	if !allowedFps[fps] || height == "" {
		log.Printf("recorder: rejected fps=%q res=%q", fps, res)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.active = true
	r.gen++
	if r.cmd != nil && r.cmd.Process != nil {
		r.cmd.Process.Signal(syscall.SIGINT) // old params — restart ffmpeg
	}
	gen := r.gen
	go r.supervise(gen, fps, height)
	log.Printf("recorder: started (fps=%s res=%s crf=%s)", fps, res, r.crf)
}

func (r *Recorder) Stop() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.active {
		return
	}
	r.active = false
	r.gen++
	if r.cmd != nil && r.cmd.Process != nil {
		// SIGINT lets ffmpeg write the mp4 trailer so the last segment plays
		r.cmd.Process.Signal(syscall.SIGINT)
	}
	log.Println("recorder: stopped")
}

// supervise restarts ffmpeg while recording is active — the process exits
// whenever the cam stops publishing (reboot, network drop) and must resume
// when the stream comes back.
func (r *Recorder) supervise(gen int, fps, height string) {
	for {
		r.mu.Lock()
		if !r.active || r.gen != gen {
			r.mu.Unlock()
			return
		}
		cmd := r.buildCmd(fps, height)
		err := cmd.Start()
		if err == nil {
			r.cmd = cmd
		}
		r.mu.Unlock()

		if err != nil {
			log.Printf("recorder: ffmpeg start: %v", err)
		} else {
			if werr := cmd.Wait(); werr != nil {
				log.Printf("recorder: ffmpeg exited: %v", werr)
			}
		}

		r.mu.Lock()
		stillActive := r.active && r.gen == gen
		r.mu.Unlock()
		if !stillActive {
			return
		}
		time.Sleep(5 * time.Second)
	}
}

func (r *Recorder) buildCmd(fps, height string) *exec.Cmd {
	out := filepath.Join(r.dir, "cam_%Y-%m-%d_%H-%M-%S.mp4")
	cmd := exec.Command("ffmpeg",
		"-hide_banner", "-loglevel", "warning",
		"-rtsp_transport", "tcp",
		"-i", r.rtspURL,
		// scale the *short* side to the target so portrait and landscape
		// sources keep comparable detail (854x480 or 480x854 for "480p")
		"-vf", "fps=" + fps + ",scale=" + height + ":" + height +
			":force_original_aspect_ratio=increase:force_divisible_by=2",
		"-c:v", "libx265", "-preset", "medium", "-crf", r.crf,
		"-tag:v", "hvc1",
		"-g", "60", // keyframe cadence so hourly segments can actually split
		"-an",
		"-f", "segment", "-segment_time", "3600",
		"-reset_timestamps", "1", "-strftime", "1",
		out,
	)
	cmd.Stdout = os.Stderr
	cmd.Stderr = os.Stderr
	return cmd
}

// pruneRecordings deletes .mp4 segments older than retentionDays by mtime.
func pruneRecordings(dir string, retentionDays int, now time.Time) error {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	cutoff := now.AddDate(0, 0, -retentionDays)
	for _, e := range entries {
		if e.IsDir() || filepath.Ext(e.Name()) != ".mp4" {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			if err := os.Remove(filepath.Join(dir, e.Name())); err != nil {
				return err
			}
		}
	}
	return nil
}
