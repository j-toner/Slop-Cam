package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Whitelists shared with the hub's command validation — recorder args are
// exec'd (no shell), but only known-good values reach ffmpeg anyway.
var allowedFps = map[string]bool{"0.5": true, "1": true, "2": true, "5": true}
var allowedRes = map[string]string{"360p": "360", "480p": "480", "720p": "720"}

// wall-clock timestamp burned along the bottom edge; single quotes shield
// the %{} block from the filtergraph parser so drawtext expands the \:
const drawtextStamp = "drawtext=fontfile=/usr/share/fonts/dejavu/DejaVuSans.ttf" +
	":text='%{localtime\\:%F %T}':fontcolor=white@0.9:fontsize=h/24" +
	":box=1:boxcolor=black@0.4:boxborderw=6:x=10:y=h-th-10"

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

// Reconnect backoff: motion-triggered starts race the cam's stream spin-up,
// so the first connects fail (404) and must be retried fast — a flat 5s
// sleep here ate half the 10s motion window and produced ~5s clips. The
// delay doubles up to the cap so a long-offline cam isn't hammered, and
// resets once ffmpeg has recorded for a while.
const (
	recorderRetryMin     = 500 * time.Millisecond
	recorderRetryMax     = 5 * time.Second
	recorderHealthyAfter = 10 * time.Second
)

// supervise restarts ffmpeg while recording is active — the process exits
// whenever the cam stops publishing (reboot, network drop) and must resume
// when the stream comes back.
func (r *Recorder) supervise(gen int, fps, height string) {
	delay := recorderRetryMin
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

		started := time.Now()
		if err != nil {
			log.Printf("recorder: ffmpeg start: %v", err)
		} else {
			if werr := cmd.Wait(); werr != nil {
				log.Printf("recorder: ffmpeg exited: %v", werr)
			}
		}
		if time.Since(started) >= recorderHealthyAfter {
			delay = recorderRetryMin // it was recording fine; reattach fast
		}

		r.mu.Lock()
		stillActive := r.active && r.gen == gen
		r.mu.Unlock()
		if !stillActive {
			return
		}
		time.Sleep(delay)
		if delay *= 2; delay > recorderRetryMax {
			delay = recorderRetryMax
		}
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
			":force_original_aspect_ratio=increase:force_divisible_by=2" +
			"," + drawtextStamp,
		"-c:v", "libx265", "-preset", "medium", "-crf", r.crf,
		"-tag:v", "hvc1",
		"-g", "60", // keyframe cadence so hourly segments can actually split
		"-an",
		"-f", "segment", "-segment_time", "3600",
		"-reset_timestamps", "1", "-strftime", "1",
		// fragmented mp4: the current segment is playable while being
		// written, and a crash/power-cut doesn't lose the whole hour
		"-segment_format_options", "movflags=+frag_keyframe+empty_moov+default_base_moof",
		out,
	)
	cmd.Stdout = os.Stderr
	cmd.Stderr = os.Stderr
	return cmd
}

// listRecordings returns segment URLs as JSON, newest first.
func listRecordings(dir string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		files := []string{} // marshal as [] instead of null when empty
		entries, _ := os.ReadDir(dir)
		for _, e := range entries {
			if !e.IsDir() && filepath.Ext(e.Name()) == ".mp4" {
				files = append(files, e.Name())
			}
		}
		sort.Sort(sort.Reverse(sort.StringSlice(files))) // names are timestamps
		for i, f := range files {
			files[i] = "/recordings/" + f
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(files)
	}
}

// ClipRecorder captures one clip at a time from the live stream with
// -c copy (full stream quality, negligible CPU): manual recordings from
// the viewer (unbounded, stopped by command) and motion-triggered clips
// (bounded by maxSeconds).
type ClipRecorder struct {
	rtspURL string
	dir     string

	mu     sync.Mutex
	active bool
	gen    int
	cmd    *exec.Cmd
}

func newClipRecorder(rtspURL, dir string) *ClipRecorder {
	return &ClipRecorder{rtspURL: rtspURL, dir: dir}
}

// Start begins capturing a clip; returns false if one is already running.
// maxSeconds <= 0 means unbounded (until Stop). onDone runs after the clip
// finishes for any reason.
func (c *ClipRecorder) Start(kind string, maxSeconds int, onDone func()) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.active {
		return false
	}
	c.active = true
	c.gen++
	go c.run(c.gen, kind, maxSeconds, onDone)
	log.Printf("clip recorder: started (%s, max %ds)", kind, maxSeconds)
	return true
}

func (c *ClipRecorder) Active() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.active
}

func (c *ClipRecorder) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.active {
		return
	}
	c.active = false
	if c.cmd != nil && c.cmd.Process != nil {
		c.cmd.Process.Signal(syscall.SIGINT)
	}
	log.Println("clip recorder: stopped")
}

func (c *ClipRecorder) run(gen int, kind string, maxSeconds int, onDone func()) {
	defer func() {
		c.mu.Lock()
		if c.gen == gen {
			c.active = false
		}
		c.mu.Unlock()
		onDone()
	}()

	// the cam may still be spinning up its stream — retry the connect
	for attempt := 0; attempt < 15; attempt++ {
		c.mu.Lock()
		if !c.active || c.gen != gen {
			c.mu.Unlock()
			return
		}
		out := filepath.Join(c.dir,
			"clip_"+kind+"_"+time.Now().Format("2006-01-02_15-04-05")+".mp4")
		// re-encode rather than -c copy: copying an RTSP session joined
		// mid-GOP leaves broken NAL units that players die on partway
		// through; a 10s x264 encode is nothing for the server and gets
		// the timestamp overlay for free
		args := []string{
			"-hide_banner", "-loglevel", "error",
			"-rtsp_transport", "tcp",
			"-i", c.rtspURL,
			"-vf", drawtextStamp,
			"-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
			"-c:a", "aac", "-b:a", "96k",
			"-movflags", "+frag_keyframe+empty_moov+default_base_moof",
		}
		if maxSeconds > 0 {
			args = append(args, "-t", strconv.Itoa(maxSeconds))
		}
		args = append(args, "-f", "mp4", out)
		cmd := exec.Command("ffmpeg", args...)
		cmd.Stderr = os.Stderr
		err := cmd.Start()
		if err == nil {
			c.cmd = cmd
		}
		c.mu.Unlock()

		if err != nil {
			log.Printf("clip recorder: ffmpeg start: %v", err)
			return
		}
		werr := cmd.Wait()
		if info, serr := os.Stat(out); serr == nil && info.Size() > 50*1024 {
			log.Printf("clip recorder: wrote %s (%d bytes)", filepath.Base(out), info.Size())
			return // got real footage, even if the stream cut out mid-clip
		}
		os.Remove(out) // connect failed / empty — retry while the cam spins up
		if werr != nil {
			log.Printf("clip recorder: attempt %d: %v", attempt+1, werr)
		}
		time.Sleep(2 * time.Second)
	}
	log.Println("clip recorder: gave up waiting for the stream")
}

// remuxFinishedSegments rewrites completed fragmented-mp4 segments as
// regular faststart mp4s. Fragmented files are crash-safe and playable
// while being written, but players can't seek them (no index); once a
// segment is done being written we trade the fragmenting for seekability.
func remuxFinishedSegments(dir string, now time.Time) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		if e.IsDir() || filepath.Ext(e.Name()) != ".mp4" {
			continue
		}
		info, err := e.Info()
		if err != nil || now.Sub(info.ModTime()) < 2*time.Minute {
			continue // still being written (or just closed — next sweep)
		}
		path := filepath.Join(dir, e.Name())
		if !isFragmentedMp4(path) {
			makeThumb(path) // backfill thumbnails for finished files
			continue        // already remuxed
		}
		tmp := path + ".remuxtmp" // non-.mp4 suffix keeps it out of listings
		cmd := exec.Command("ffmpeg", "-y", "-v", "error",
			"-i", path, "-c", "copy", "-movflags", "+faststart", "-f", "mp4", tmp)
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			log.Printf("remux %s: %v", e.Name(), err)
			os.Remove(tmp)
			continue
		}
		if err := os.Rename(tmp, path); err != nil {
			log.Printf("remux rename %s: %v", e.Name(), err)
			os.Remove(tmp)
			continue
		}
		log.Printf("remuxed %s for seeking", e.Name())
		makeThumb(path)
	}
}

// makeThumb writes a small first-frame jpg next to the video ("<name>.jpg")
// for the gallery grid; no-op if it already exists.
func makeThumb(path string) {
	thumb := path + ".jpg"
	if _, err := os.Stat(thumb); err == nil {
		return
	}
	cmd := exec.Command("ffmpeg", "-y", "-v", "error",
		"-i", path, "-frames:v", "1", "-vf", "scale=320:-2", thumb)
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		log.Printf("thumb %s: %v", filepath.Base(path), err)
		os.Remove(thumb)
	}
}

// isFragmentedMp4 sniffs for the mvex box that only fragmented files
// carry in their (front-loaded) moov.
func isFragmentedMp4(path string) bool {
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	buf := make([]byte, 4096)
	n, _ := f.Read(buf)
	return n > 0 && strings.Contains(string(buf[:n]), "mvex")
}

// pruneRecordings deletes .mp4 segments older than retentionDays by mtime.
func pruneRecordings(dir string, retentionDays int, now time.Time) error {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	cutoff := now.AddDate(0, 0, -retentionDays)
	for _, e := range entries {
		ext := filepath.Ext(e.Name())
		if e.IsDir() || (ext != ".mp4" && ext != ".jpg") {
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
