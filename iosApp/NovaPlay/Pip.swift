import UIKit
import AVKit
import Shared

/// The small window in the corner of the screen. It lives apart from the player screen: once it is up, the player
/// screen closes and the app goes to the home screen, while the small window keeps playing over everything.
/// AVPlayer plays it (VLC can't run picture in picture); the player screen lets go of its own stream first, since many
/// playlists allow only one stream at a time.
final class PipKeeper: NSObject, AVPictureInPictureControllerDelegate {
    static let shared = PipKeeper()

    private var player: AVPlayer?
    private var layer: AVPlayerLayer?
    /// The layer has to stay in a window to play: a tiny, almost clear view in the app's window, which outlives the
    /// player screen.
    private let host = UIView(frame: CGRect(x: 0, y: 0, width: 4, height: 4))
    private var controller: AVPictureInPictureController?
    private var watch: NSKeyValueObservation?
    private var stall: NSObjectProtocol?
    private var restoring = false
    private var onStarted: (() -> Void)?
    private var onFailed: (() -> Void)?
    private(set) var request: PlayRequest?

    var isActive: Bool { controller?.isPictureInPictureActive ?? false }

    /// AVPlayer plays HLS and MP4: an Xtream channel's .ts address has an .m3u8 twin.
    static func url(for request: PlayRequest) -> URL? {
        var u = request.url
        if request.live, let q = u.range(of: ".ts", options: [.backwards]), u[q.upperBound...].isEmpty || u[q.upperBound...].hasPrefix("?") {
            u.replaceSubrange(q, with: ".m3u8")
        }
        return URL(string: u)
    }

    /// Servers whose .m3u8 is a real live playlist (true) or a stub (false), by host: asked once per server.
    private static var hlsByHost: [String: Bool] = [:]

    /**
     * Whether [url] is a live HLS playlist AVPlayer can follow. Some servers answer .m3u8 with a stub: one "10 second"
     * segment that is really the endless .ts stream, then #EXT-X-ENDLIST. AVPlayer takes that for a finished clip and
     * stalls and restarts all the time; VLC plays the .ts itself smoothly. Calls [done] on the main queue.
     */
    static func isLiveHls(_ url: URL, userAgent: String, done: @escaping (Bool) -> Void) {
        let host = url.host ?? ""
        if let known = hlsByHost[host] { done(known); return }
        var req = URLRequest(url: url, timeoutInterval: 5)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: req) { data, _, _ in
            let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            let segments = text.split(separator: "\n").filter { !$0.hasPrefix("#") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }.count
            let live = text.hasPrefix("#EXTM3U") && !(text.contains("#EXT-X-ENDLIST") && segments <= 1)
            DispatchQueue.main.async {
                if !text.isEmpty { hlsByHost[host] = live }
                done(live)
            }
        }.resume()
    }

    func start(_ request: PlayRequest, at ms: Int64, started: @escaping () -> Void, failed: @escaping () -> Void) {
        stop()
        guard AVPictureInPictureController.isPictureInPictureSupported(), let url = PipKeeper.url(for: request) else { failed(); return }
        // A live channel whose server has no real HLS can't go in the small window without stalling.
        if request.live && url.absoluteString != request.url {
            PipKeeper.isLiveHls(url, userAgent: request.userAgent) { [weak self] ok in
                if ok { self?.begin(request, url: url, at: ms, started: started, failed: failed) } else { failed() }
            }
            return
        }
        begin(request, url: url, at: ms, started: started, failed: failed)
    }

    private func begin(_ request: PlayRequest, url: URL, at ms: Int64, started: @escaping () -> Void, failed: @escaping () -> Void) {
        self.request = request
        onStarted = started
        onFailed = failed
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        if host.superview == nil, let w = PipKeeper.keyWindow() {
            host.alpha = 0.01
            host.isUserInteractionEnabled = false
            w.addSubview(host)
        }
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": request.userAgent]])
        let item = AVPlayerItem(asset: asset)
        let av = AVPlayer(playerItem: item)
        av.automaticallyWaitsToMinimizeStalling = true
        let l = AVPlayerLayer(player: av)
        l.frame = host.bounds
        host.layer.addSublayer(l)
        player = av
        layer = l
        // A stream that stalls carries on by itself rather than sitting paused in the corner.
        stall = NotificationCenter.default.addObserver(forName: .AVPlayerItemPlaybackStalled, object: item, queue: .main) { [weak self] _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self?.player?.play() }
        }
        watch = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            DispatchQueue.main.async {
                guard let self = self, self.player === av else { return }
                if it.status == .failed { self.fail(); return }
                guard it.status == .readyToPlay, self.controller == nil else { return }
                if ms > 0 { av.seek(to: CMTime(value: ms, timescale: 1000)) }
                av.play()
                let c = AVPictureInPictureController(playerLayer: l)
                c?.delegate = self
                self.controller = c
                // The controller needs a moment before it can start.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    if let c = c { c.startPictureInPicture() } else { self.fail() }
                }
            }
        }
        av.play()
    }

    private func fail() {
        let f = onFailed
        stop()
        f?()
    }

    /// Where a film or episode has got to, in milliseconds.
    func positionMs() -> Int64 {
        let s = player?.currentTime().seconds ?? 0
        return s.isFinite ? Int64(s * 1000) : 0
    }

    private func durationMs() -> Int64 {
        let s = player?.currentItem?.duration.seconds ?? 0
        return s.isFinite ? Int64(s * 1000) : 0
    }

    func stop() {
        watch = nil
        if let s = stall { NotificationCenter.default.removeObserver(s); stall = nil }
        if let c = controller {
            c.delegate = nil
            if c.isPictureInPictureActive { c.stopPictureInPicture() }
        }
        controller = nil
        player?.pause()
        player = nil
        layer?.removeFromSuperlayer()
        layer = nil
        request = nil
        restoring = false
        onStarted = nil
        onFailed = nil
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ c: AVPictureInPictureController) {
        player?.play()
        let s = onStarted
        onStarted = nil
        s?()
    }

    func pictureInPictureControllerFailedToStartPictureInPicture(_ c: AVPictureInPictureController, withError error: Error) { fail() }

    /// The button in the small window that goes back to the app: the player screen opens again, where it got to.
    func pictureInPictureController(_ c: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler done: @escaping (Bool) -> Void) {
        restoring = true
        if let r = request {
            let at = r.live ? Int64(0) : positionMs()
            // Its stream is let go first, so the full player can open its own.
            player?.pause()
            PlayerHost.shared.reopen(r, atMs: at)
        }
        done(true)
    }

    /// Closed (×) or gone back to the app.
    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        if !restoring, let r = request, !r.live {
            let d = durationMs()
            if d > 0 { IosPlayback.shared.saveProgress(posMs: positionMs(), durMs: d) }
        }
        stop()
    }

    static func keyWindow() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap { $0.windows }.first { $0.isKeyWindow } ?? scenes.first?.windows.first
    }

    /// Sends the app to the home screen, as the Home gesture does; the small window stays up.
    static func goHome() {
        UIControl().sendAction(#selector(URLSessionTask.suspend), to: UIApplication.shared, for: nil)
    }
}
