import UIKit
import MobileVLCKit
import Shared

/// Full-screen playback with VLC: live TS/HLS channels and films.
/// Tap shows the controls; swipe up or down changes channel on live TV.
final class PlayerViewController: UIViewController, VLCMediaPlayerDelegate {
    private let player = VLCMediaPlayer()
    private let video = UIView()
    private var request: PlayRequest

    private let overlay = UIView()
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let playButton = UIButton(type: .system)
    private let prevButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private let slider = UISlider()
    private let timeLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let errorLabel = UILabel()
    private var hideTimer: Timer?
    private var progressTimer: Timer?
    private var seeking = false
    private var didResume = false

    init(request: PlayRequest) {
        self.request = request
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        video.frame = view.bounds
        video.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(video)
        player.drawable = video
        player.delegate = self

        spinner.color = .white
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)

        errorLabel.textColor = .white
        errorLabel.font = .systemFont(ofSize: 18, weight: .semibold)
        errorLabel.textAlignment = .center
        errorLabel.numberOfLines = 0
        errorLabel.isHidden = true
        errorLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(errorLabel)

        buildOverlay()
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            errorLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            errorLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: 40),
            errorLabel.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, multiplier: 0.8),
        ])

        view.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        let up = UISwipeGestureRecognizer(target: self, action: #selector(swipedUp)); up.direction = .up
        let down = UISwipeGestureRecognizer(target: self, action: #selector(swipedDown)); down.direction = .down
        view.addGestureRecognizer(up)
        view.addGestureRecognizer(down)

        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.tick() }
        load(request)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        saveProgress()
        progressTimer?.invalidate()
        hideTimer?.invalidate()
        player.stop()
    }

    /// Plays [request]: the first stream, or the next channel when zapping.
    func load(_ request: PlayRequest) {
        saveProgress()
        self.request = request
        didResume = false
        errorLabel.isHidden = true
        spinner.startAnimating()
        titleLabel.text = request.title
        subtitleLabel.text = request.subtitle
        prevButton.isHidden = !request.live
        nextButton.isHidden = !request.live
        slider.isHidden = request.live
        timeLabel.isHidden = request.live
        guard let url = URL(string: request.url) else { showError("This stream address is not valid"); return }
        let media = VLCMedia(url: url)
        media.addOption(":http-user-agent=\(request.userAgent)")
        media.addOption(":network-caching=\(max(1000, min(15000, Int(request.bufferMs))))")
        player.media = media
        player.play()
        showControls()
    }

    func showError(_ message: String) {
        spinner.stopAnimating()
        errorLabel.text = message + (request.live ? "\nSwipe up or down for another channel." : "")
        errorLabel.isHidden = false
        showControls(stay: true)
    }

    // MARK: VLC

    func mediaPlayerStateChanged(_ aNotification: Notification) {
        switch player.state {
        case .playing:
            spinner.stopAnimating(); errorLabel.isHidden = true
            if !didResume && !request.live && request.startMs > 0 {
                didResume = true
                player.time = VLCTime(int: Int32(clamping: request.startMs))
            }
        case .buffering, .opening:
            if !player.isPlaying { spinner.startAnimating() }
        case .error:
            showError("This stream could not be played")
        case .ended, .stopped:
            if !request.live, player.state == .ended {
                let dur = Int64(player.media?.length.intValue ?? 0)
                if !IosPlayback.shared.ended(durMs: dur) { dismiss(animated: true) }
            }
        default: break
        }
        playButton.setTitle(player.isPlaying ? "Pause" : "Play", for: .normal)
    }

    func mediaPlayerTimeChanged(_ aNotification: Notification) {
        if spinner.isAnimating { spinner.stopAnimating() }
    }

    private func tick() {
        guard !request.live, !seeking, let length = player.media?.length.intValue, length > 0 else { return }
        let pos = player.time.intValue
        slider.value = Float(pos) / Float(length)
        timeLabel.text = "\(format(Int(pos))) / \(format(Int(length)))"
    }

    private func saveProgress() {
        guard !request.live, let length = player.media?.length.intValue, length > 0 else { return }
        IosPlayback.shared.saveProgress(posMs: Int64(player.time.intValue), durMs: Int64(length))
    }

    private func format(_ ms: Int) -> String {
        let s = ms / 1000
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    // MARK: Controls

    private func buildOverlay() {
        overlay.frame = view.bounds
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        overlay.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        view.addSubview(overlay)

        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 22, weight: .semibold)
        subtitleLabel.textColor = UIColor(white: 0.8, alpha: 1)
        subtitleLabel.font = .systemFont(ofSize: 15)
        timeLabel.textColor = UIColor(white: 0.85, alpha: 1)
        timeLabel.font = .monospacedDigitSystemFont(ofSize: 14, weight: .regular)

        style(closeButton, "Close"); closeButton.addTarget(self, action: #selector(close), for: .touchUpInside)
        style(playButton, "Pause"); playButton.addTarget(self, action: #selector(togglePlay), for: .touchUpInside)
        style(prevButton, "▲ Previous"); prevButton.addTarget(self, action: #selector(swipedDown), for: .touchUpInside)
        style(nextButton, "Next ▼"); nextButton.addTarget(self, action: #selector(swipedUp), for: .touchUpInside)
        slider.minimumTrackTintColor = UIColor(red: 0.36, green: 0.55, blue: 1, alpha: 1)
        slider.addTarget(self, action: #selector(seekStart), for: .touchDown)
        slider.addTarget(self, action: #selector(seekEnd), for: [.touchUpInside, .touchUpOutside, .touchCancel])

        let top = UIStackView(arrangedSubviews: [closeButton, titleLabel, UIView()])
        top.axis = .horizontal; top.spacing = 16; top.alignment = .center
        let bottomRow = UIStackView(arrangedSubviews: [playButton, prevButton, nextButton, slider, timeLabel])
        bottomRow.axis = .horizontal; bottomRow.spacing = 14; bottomRow.alignment = .center
        let bottom = UIStackView(arrangedSubviews: [subtitleLabel, bottomRow])
        bottom.axis = .vertical; bottom.spacing = 10

        for v in [top, bottom] { v.translatesAutoresizingMaskIntoConstraints = false; overlay.addSubview(v) }
        let g = overlay.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            top.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 20),
            top.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -20),
            top.topAnchor.constraint(equalTo: g.topAnchor, constant: 16),
            bottom.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 20),
            bottom.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -20),
            bottom.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -16),
        ])
    }

    private func style(_ b: UIButton, _ title: String) {
        b.setTitle(title, for: .normal)
        b.setTitleColor(.white, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        b.backgroundColor = UIColor(white: 1, alpha: 0.16)
        b.layer.cornerRadius = 18
        b.contentEdgeInsets = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)
    }

    private func showControls(stay: Bool = false) {
        overlay.isHidden = false
        hideTimer?.invalidate()
        if !stay { hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in self?.overlay.isHidden = true } }
    }

    @objc private func tapped() { if overlay.isHidden { showControls() } else { overlay.isHidden = true } }
    @objc private func swipedUp() { if request.live { IosPlayback.shared.zap(delta: 1) } }
    @objc private func swipedDown() { if request.live { IosPlayback.shared.zap(delta: -1) } }
    @objc private func close() { dismiss(animated: true) }
    @objc private func togglePlay() { if player.isPlaying { player.pause() } else { player.play() }; showControls() }
    @objc private func seekStart() { seeking = true; hideTimer?.invalidate() }
    @objc private func seekEnd() {
        if let length = player.media?.length.intValue, length > 0 {
            player.time = VLCTime(int: Int32(clamping: Int(Float(length) * slider.value)))
        }
        seeking = false
        showControls()
    }
}
