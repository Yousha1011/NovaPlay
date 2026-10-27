import UIKit
import MobileVLCKit
import Shared

/// The player, in two layouts like IM Player's:
/// portrait, the picture on top with the channel's details, its guide and "More from" its category below;
/// landscape, the picture full screen with floating controls, brightness and volume sliders and a More Channels drawer.
final class PlayerViewController: UIViewController, VLCMediaPlayerDelegate, UICollectionViewDataSource, UICollectionViewDelegate, UIGestureRecognizerDelegate {
    private let player = VLCMediaPlayer()
    private var request: PlayRequest

    // The picture and its controls.
    private let videoBox = UIView()
    private let video = UIView()
    private let controls = UIView()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let errorLabel = UILabel()

    // Top of the picture.
    private lazy var closeButton = roundButton("xmark", target: self, action: #selector(close))
    private lazy var backButton = roundButton("chevron.left", target: self, action: #selector(close))
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let topBar = UIStackView()
    private lazy var infoButton = iconButton("info.circle", target: self, action: #selector(showInfo))
    private lazy var favButton = iconButton("heart", target: self, action: #selector(toggleFavourite))
    private lazy var rotateButton = iconButton("rectangle.portrait.rotate", target: self, action: #selector(rotate))
    private lazy var expandButton = iconButton("arrow.up.left.and.arrow.down.right", target: self, action: #selector(rotate))
    private let settingsButton = UIButton(type: .system)

    // Middle.
    private let logoView = UIImageView()
    private lazy var prevButton = roundButton("backward.end.fill", size: 64, point: 24, target: self, action: #selector(prevChannel))
    private lazy var playButton = roundButton("pause.fill", size: 76, point: 30, target: self, action: #selector(togglePlay))
    private lazy var nextButton = roundButton("forward.end.fill", size: 64, point: 24, target: self, action: #selector(nextChannel))
    private lazy var backSkip = roundButton("gobackward.15", size: 64, point: 24, target: self, action: #selector(skipBack))
    private lazy var forwardSkip = roundButton("goforward.15", size: 64, point: 24, target: self, action: #selector(skipForward))
    private let brightness = VerticalSlider(symbol: "sun.max.fill")
    private let volume = VerticalSlider(symbol: "speaker.wave.2.fill")

    // Bottom.
    private let startLabel = UILabel()
    private let endLabel = UILabel()
    private let timeline = UISlider()
    private let resPill = StatPill()
    private let fpsPill = StatPill()
    private let ratePill = StatPill()
    private lazy var lockButton = roundButton("lock.fill", size: 40, point: 15, target: self, action: #selector(toggleLock))
    private let speedButton = UIButton(type: .system)
    private let moreButton = UIButton(type: .system)

    // Portrait page under the picture.
    private let page = UIScrollView()
    private let pageStack = UIStackView()
    private let pageLogo = UIImageView()
    private let pageName = UILabel()
    private let pageNow = UILabel()
    private let pageNext = UILabel()
    private let pageProgress = UIProgressView(progressViewStyle: .bar)
    private let guideStack = UIStackView()
    private let moreTitle = UILabel()
    private lazy var moreCards = makeCards()

    // More Channels drawer (landscape).
    private let drawer = UIView()
    private let tabsStack = UIStackView()
    private lazy var drawerCards = makeCards()
    private var categories: [CategoryTab] = []
    private var drawerCategory: String = ""
    private var drawerChannels: [ChannelCard] = []
    private var moreChannels: [ChannelCard] = []

    private var portraitConstraints: [NSLayoutConstraint] = []
    private var landscapeConstraints: [NSLayoutConstraint] = []
    private var hideTimer: Timer?
    private var tickTimer: Timer?
    private var seeking = false
    private var didResume = false
    private var locked = false
    private var aspect: String? = nil

    init(request: PlayRequest) {
        self.request = request
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .allButUpsideDown }
    override var prefersStatusBarHidden: Bool { isLandscape }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    private var isLandscape: Bool { view.bounds.width > view.bounds.height }

    // MARK: Building the screen

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        overrideUserInterfaceStyle = .dark

        buildPage()
        buildVideo()
        buildControls()
        buildDrawer()

        let g = view.safeAreaLayoutGuide
        portraitConstraints = [
            videoBox.topAnchor.constraint(equalTo: g.topAnchor),
            videoBox.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            videoBox.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            videoBox.heightAnchor.constraint(equalTo: videoBox.widthAnchor, multiplier: 9.0 / 16.0),
        ]
        landscapeConstraints = [
            videoBox.topAnchor.constraint(equalTo: view.topAnchor),
            videoBox.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            videoBox.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            videoBox.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ]
        NSLayoutConstraint.activate([
            page.topAnchor.constraint(equalTo: videoBox.bottomAnchor),
            page.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            page.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            page.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        player.drawable = video
        player.delegate = self
        brightness.value = Float(UIScreen.main.brightness)
        brightness.onChange = { UIScreen.main.brightness = CGFloat($0) }
        volume.value = 1
        volume.onChange = { [weak self] v in self?.player.audio?.volume = Int32(v * 100); self?.volume.symbol = v < 0.01 ? "speaker.slash.fill" : "speaker.wave.2.fill" }

        tickTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        applyLayout()
        load(request)
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: { _ in self.applyLayout() })
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let land = isLandscape
        if land != (landscapeConstraints.first?.isActive ?? false) { applyLayout() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        saveProgress()
        tickTimer?.invalidate()
        hideTimer?.invalidate()
        player.stop()
    }

    /// Switches between the portrait page and the full-screen controls.
    private func applyLayout() {
        let land = isLandscape
        NSLayoutConstraint.deactivate(land ? portraitConstraints : landscapeConstraints)
        NSLayoutConstraint.activate(land ? landscapeConstraints : portraitConstraints)
        page.isHidden = land
        for v: UIView in [closeButton, titleLabel, subtitleLabel, infoButton, rotateButton, settingsButton, logoView, prevButton, nextButton,
                          brightness, volume, resPill, fpsPill, ratePill, lockButton, moreButton] { v.isHidden = !land }
        backButton.isHidden = land
        expandButton.isHidden = land
        speedButton.isHidden = true
        backSkip.isHidden = request.live
        forwardSkip.isHidden = request.live
        prevButton.isHidden = !land || !request.live
        nextButton.isHidden = !land || !request.live
        moreButton.isHidden = !land || !request.live
        if !land { drawer.isHidden = true }
        setNeedsStatusBarAppearanceUpdate()
        showControls()
    }

    private func label(_ l: UILabel, size: CGFloat, weight: UIFont.Weight, color: UIColor = .white, lines: Int = 1) {
        l.font = .systemFont(ofSize: size, weight: weight)
        l.textColor = color
        l.numberOfLines = lines
        l.translatesAutoresizingMaskIntoConstraints = false
    }

    private func buildVideo() {
        videoBox.backgroundColor = .black
        videoBox.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(videoBox)
        videoBox.pin(video)
        video.isUserInteractionEnabled = false
        // Taps are caught on the whole screen: once VLC starts drawing it adds its own views, which could swallow taps
        // meant for the picture. Taps on buttons, sliders and lists are left to them (see gestureRecognizer below).
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
        tap.cancelsTouchesInView = false
        tap.delegate = self
        view.addGestureRecognizer(tap)
    }

    private func buildControls() {
        videoBox.pin(controls)
        controls.backgroundColor = UIColor.black.withAlphaComponent(0.25)

        spinner.color = .white
        spinner.translatesAutoresizingMaskIntoConstraints = false
        videoBox.addSubview(spinner)
        label(errorLabel, size: 17, weight: .semibold, lines: 0)
        errorLabel.textAlignment = .center
        errorLabel.isHidden = true
        videoBox.addSubview(errorLabel)

        // Top left: close (landscape) or back (portrait), then the title.
        label(titleLabel, size: 22, weight: .bold)
        label(subtitleLabel, size: 15, weight: .regular, color: UIColor(white: 0.85, alpha: 1))
        let titles = UIStackView(arrangedSubviews: [titleLabel, subtitleLabel])
        titles.axis = .vertical; titles.spacing = 2
        let topLeft = UIStackView(arrangedSubviews: [closeButton, backButton, titles])
        topLeft.axis = .horizontal; topLeft.spacing = 14; topLeft.alignment = .center
        topLeft.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(topLeft)

        // Top right: a frosted bar of buttons.
        settingsButton.setImage(UIImage(systemName: "gearshape", withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .semibold)), for: .normal)
        settingsButton.tintColor = .white
        settingsButton.showsMenuAsPrimaryAction = true
        settingsButton.menu = UIMenu(children: [UIDeferredMenuElement.uncached { [weak self] done in done(self?.settingsMenu() ?? []) }])
        settingsButton.translatesAutoresizingMaskIntoConstraints = false
        settingsButton.widthAnchor.constraint(equalToConstant: 44).isActive = true
        for b in [infoButton, favButton, rotateButton, settingsButton, expandButton] { topBar.addArrangedSubview(b) }
        topBar.axis = .horizontal; topBar.spacing = 6
        topBar.isLayoutMarginsRelativeArrangement = true
        topBar.layoutMargins = UIEdgeInsets(top: 2, left: 10, bottom: 2, right: 10)
        topBar.layer.cornerRadius = 22; topBar.clipsToBounds = true
        _ = topBar.glass(radius: 22)
        topBar.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(topBar)

        // Middle: the logo over previous / play / next.
        logoView.contentMode = .scaleAspectFit
        logoView.translatesAutoresizingMaskIntoConstraints = false
        let middle = UIStackView(arrangedSubviews: [prevButton, backSkip, playButton, forwardSkip, nextButton])
        middle.axis = .horizontal; middle.spacing = 28; middle.alignment = .center
        middle.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(logoView)
        controls.addSubview(middle)
        controls.addSubview(brightness)
        controls.addSubview(volume)

        // Bottom: the timeline, the stream's figures, speed, lock and More Channels.
        label(startLabel, size: 14, weight: .medium); label(endLabel, size: 14, weight: .medium)
        startLabel.font = .monospacedDigitSystemFont(ofSize: 14, weight: .medium)
        endLabel.font = .monospacedDigitSystemFont(ofSize: 14, weight: .medium)
        timeline.minimumTrackTintColor = Palette.accent
        timeline.maximumTrackTintColor = UIColor(white: 1, alpha: 0.3)
        timeline.setThumbImage(UIImage(), for: .normal)
        timeline.addTarget(self, action: #selector(seekStart), for: .touchDown)
        timeline.addTarget(self, action: #selector(seekEnd), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        let timeRow = UIStackView(arrangedSubviews: [startLabel, timeline, endLabel])
        timeRow.axis = .horizontal; timeRow.spacing = 12; timeRow.alignment = .center

        speedButton.setTitle("1×", for: .normal)
        speedButton.setTitleColor(.white, for: .normal)
        speedButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .bold)
        speedButton.backgroundColor = UIColor(white: 1, alpha: 0.14)
        speedButton.layer.cornerRadius = 20
        speedButton.translatesAutoresizingMaskIntoConstraints = false
        speedButton.widthAnchor.constraint(equalToConstant: 52).isActive = true
        speedButton.heightAnchor.constraint(equalToConstant: 40).isActive = true
        speedButton.showsMenuAsPrimaryAction = true
        speedButton.menu = UIMenu(title: "Speed", children: [0.5, 0.75, 1, 1.25, 1.5, 2].map { r in
            UIAction(title: "\(r)×") { [weak self] _ in self?.player.rate = Float(r); self?.speedButton.setTitle("\(r == 1 ? "1" : String(r))×", for: .normal) }
        })

        var more = UIButton.Configuration.plain()
        more.image = UIImage(systemName: "list.bullet", withConfiguration: UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold))
        more.imagePadding = 10
        more.title = "More Channels"
        more.baseForegroundColor = Palette.accent
        more.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 18, bottom: 10, trailing: 18)
        moreButton.configuration = more
        moreButton.layer.cornerRadius = 22; moreButton.clipsToBounds = true
        _ = moreButton.glass(radius: 22)
        moreButton.addTarget(self, action: #selector(openDrawer), for: .touchUpInside)

        let pills = UIStackView(arrangedSubviews: [resPill, fpsPill, ratePill, UIView(), speedButton, moreButton])
        pills.axis = .horizontal; pills.spacing = 10; pills.alignment = .center
        let bottom = UIStackView(arrangedSubviews: [timeRow, pills])
        bottom.axis = .vertical; bottom.spacing = 12
        bottom.translatesAutoresizingMaskIntoConstraints = false
        controls.addSubview(bottom)
        controls.addSubview(lockButton)

        let g = controls.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            topLeft.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16),
            topLeft.topAnchor.constraint(equalTo: g.topAnchor, constant: 12),
            topLeft.trailingAnchor.constraint(lessThanOrEqualTo: topBar.leadingAnchor, constant: -12),
            topBar.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -16),
            topBar.topAnchor.constraint(equalTo: g.topAnchor, constant: 12),
            topBar.heightAnchor.constraint(equalToConstant: 44),
            middle.centerXAnchor.constraint(equalTo: controls.centerXAnchor),
            middle.centerYAnchor.constraint(equalTo: controls.centerYAnchor),
            logoView.centerXAnchor.constraint(equalTo: controls.centerXAnchor),
            logoView.bottomAnchor.constraint(equalTo: middle.topAnchor, constant: -16),
            logoView.widthAnchor.constraint(equalToConstant: 84),
            logoView.heightAnchor.constraint(equalToConstant: 84),
            brightness.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 120),
            brightness.centerYAnchor.constraint(equalTo: controls.centerYAnchor),
            volume.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -120),
            volume.centerYAnchor.constraint(equalTo: controls.centerYAnchor),
            bottom.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16),
            bottom.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -16),
            bottom.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -10),
            lockButton.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -16),
            lockButton.bottomAnchor.constraint(equalTo: bottom.topAnchor, constant: -14),
            spinner.centerXAnchor.constraint(equalTo: videoBox.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: videoBox.centerYAnchor),
            errorLabel.centerXAnchor.constraint(equalTo: videoBox.centerXAnchor),
            errorLabel.topAnchor.constraint(equalTo: spinner.bottomAnchor, constant: 16),
            errorLabel.widthAnchor.constraint(lessThanOrEqualTo: videoBox.widthAnchor, multiplier: 0.8),
        ])
    }

    private func buildPage() {
        page.backgroundColor = .black
        page.translatesAutoresizingMaskIntoConstraints = false
        page.alwaysBounceVertical = true
        view.addSubview(page)
        pageStack.axis = .vertical; pageStack.spacing = 18
        pageStack.isLayoutMarginsRelativeArrangement = true
        pageStack.layoutMargins = UIEdgeInsets(top: 22, left: 16, bottom: 30, right: 16)
        page.pin(pageStack)
        pageStack.widthAnchor.constraint(equalTo: page.widthAnchor).isActive = true

        // The channel: logo, name, now and next, progress.
        pageLogo.contentMode = .scaleAspectFit
        pageLogo.translatesAutoresizingMaskIntoConstraints = false
        pageLogo.widthAnchor.constraint(equalToConstant: 84).isActive = true
        pageLogo.heightAnchor.constraint(equalToConstant: 84).isActive = true
        label(pageName, size: 24, weight: .bold, lines: 2)
        label(pageNow, size: 17, weight: .semibold)
        label(pageNext, size: 15, weight: .regular, color: Palette.dim)
        pageProgress.trackTintColor = UIColor(white: 1, alpha: 0.18)
        pageProgress.progressTintColor = Palette.accent
        let texts = UIStackView(arrangedSubviews: [pageName, pageNow, pageNext, pageProgress])
        texts.axis = .vertical; texts.spacing = 6
        texts.setCustomSpacing(12, after: pageName)
        let info = UIStackView(arrangedSubviews: [pageLogo, texts])
        info.axis = .horizontal; info.spacing = 16; info.alignment = .center
        pageStack.addArrangedSubview(info)
        pageStack.addArrangedSubview(separator())

        // The guide.
        guideStack.axis = .vertical; guideStack.spacing = 0
        pageStack.addArrangedSubview(guideStack)
        pageStack.addArrangedSubview(separator())

        // More from the category.
        label(moreTitle, size: 20, weight: .bold)
        pageStack.addArrangedSubview(moreTitle)
        moreCards.heightAnchor.constraint(equalToConstant: 150).isActive = true
        pageStack.addArrangedSubview(moreCards)
    }

    private func separator() -> UIView {
        let v = UIView()
        v.backgroundColor = UIColor(white: 1, alpha: 0.1)
        v.heightAnchor.constraint(equalToConstant: 1).isActive = true
        return v
    }

    private func makeCards() -> UICollectionView {
        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .horizontal
        layout.itemSize = CGSize(width: 190, height: 150)
        layout.minimumLineSpacing = 12
        let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
        cv.backgroundColor = .clear
        cv.showsHorizontalScrollIndicator = false
        cv.register(ChannelCell.self, forCellWithReuseIdentifier: "c")
        cv.dataSource = self
        cv.delegate = self
        cv.translatesAutoresizingMaskIntoConstraints = false
        return cv
    }

    private func buildDrawer() {
        drawer.isHidden = true
        drawer.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        videoBox.pin(drawer)
        drawer.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(closeDrawer)))

        let tabs = UIScrollView()
        tabs.showsHorizontalScrollIndicator = false
        tabsStack.axis = .horizontal; tabsStack.spacing = 12
        tabsStack.isLayoutMarginsRelativeArrangement = true
        tabsStack.layoutMargins = UIEdgeInsets(top: 0, left: 16, bottom: 0, right: 16)
        tabs.pin(tabsStack)
        tabsStack.heightAnchor.constraint(equalTo: tabs.heightAnchor).isActive = true
        tabs.translatesAutoresizingMaskIntoConstraints = false
        drawer.addSubview(tabs)
        drawer.addSubview(drawerCards)
        let g = drawer.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            tabs.topAnchor.constraint(equalTo: g.topAnchor, constant: 16),
            tabs.leadingAnchor.constraint(equalTo: drawer.leadingAnchor),
            tabs.trailingAnchor.constraint(equalTo: drawer.trailingAnchor),
            tabs.heightAnchor.constraint(equalToConstant: 46),
            drawerCards.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16),
            drawerCards.trailingAnchor.constraint(equalTo: g.trailingAnchor),
            drawerCards.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -12),
            drawerCards.heightAnchor.constraint(equalToConstant: 160),
        ])
    }

    // MARK: Playing

    /// Plays [request]: the first stream, or another channel when zapping.
    func load(_ request: PlayRequest) {
        saveProgress()
        self.request = request
        didResume = false
        errorLabel.isHidden = true
        spinner.startAnimating()
        titleLabel.text = request.live ? request.channelName : request.title
        subtitleLabel.text = request.live ? request.nowTitle : request.subtitle
        pageName.text = request.live ? request.channelName : request.title
        pageNow.text = request.live ? request.nowTitle : request.subtitle
        pageNext.text = request.live ? request.nextTitle : ""
        pageProgress.progress = max(0, request.nowProgress)
        pageProgress.isHidden = request.nowProgress < 0
        Images.shared.load(request.logo, into: logoView)
        Images.shared.load(request.logo, into: pageLogo)
        setFavourite(request.favourite)
        favButton.isHidden = !request.live
        infoButton.isEnabled = true
        applyLayout()
        loadGuide()
        loadMore()

        guard let url = URL(string: request.url) else { showError("This stream address is not valid"); return }
        let media = VLCMedia(url: url)
        media.addOption(":http-user-agent=\(request.userAgent)")
        media.addOption(":network-caching=\(max(1000, min(15000, Int(request.bufferMs))))")
        player.media = media
        if let a = aspect { player.videoAspectRatio = UnsafeMutablePointer<CChar>(mutating: (a as NSString).utf8String) }
        player.play()
        showControls()
    }

    func showError(_ message: String) {
        spinner.stopAnimating()
        errorLabel.text = message
        errorLabel.isHidden = false
        showControls(stay: true)
    }

    func mediaPlayerStateChanged(_ aNotification: Notification) {
        switch player.state {
        case .playing:
            spinner.stopAnimating(); errorLabel.isHidden = true
            // VLC's own views inside the picture must never take touches.
            video.subviews.forEach { $0.isUserInteractionEnabled = false }
            if !didResume && !request.live && request.startMs > 0 {
                didResume = true
                player.time = VLCTime(int: Int32(clamping: request.startMs))
            }
        case .buffering, .opening:
            if !player.isPlaying { spinner.startAnimating() }
        case .error:
            showError("This stream could not be played")
        case .ended:
            if !request.live {
                let dur = Int64(player.media?.length.intValue ?? 0)
                if !IosPlayback.shared.ended(durMs: dur) { dismiss(animated: true) }
            }
        default: break
        }
        updatePlayIcon()
    }

    func mediaPlayerTimeChanged(_ aNotification: Notification) {
        if spinner.isAnimating { spinner.stopAnimating() }
    }

    private func updatePlayIcon() {
        let name = player.isPlaying ? "pause.fill" : "play.fill"
        playButton.setImage(UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: 30, weight: .semibold)), for: .normal)
    }

    /// Once a second: the timeline and the stream's figures.
    private func tick() {
        if request.live {
            // Live TV: how far through the programme on now.
            timeline.isUserInteractionEnabled = false
            timeline.value = max(0, request.nowProgress)
            let parts = request.nowTimes.components(separatedBy: " – ")
            startLabel.text = parts.first ?? ""
            endLabel.text = parts.count > 1 ? parts[1] : ""
        } else if !seeking, let length = player.media?.length.intValue, length > 0 {
            timeline.isUserInteractionEnabled = true
            let pos = player.time.intValue
            timeline.value = Float(pos) / Float(length)
            startLabel.text = format(Int(pos))
            endLabel.text = format(Int(length))
        }
        let size = player.videoSize
        if size.width > 0 {
            let h = Int(size.height)
            let tag = h >= 2000 ? "4K" : h >= 1000 ? "FHD" : h >= 700 ? "HD" : "SD"
            resPill.text = "\(Int(size.width)) x \(h) (\(tag))"
        } else { resPill.text = "–" }
        fpsPill.text = fps().map { "\(Int($0.rounded())) FPS" } ?? "– FPS"
        let mbps = Double(player.media?.statistics.inputBitrate ?? 0) * 8
        ratePill.text = mbps >= 1 ? String(format: "%.1fMbps", mbps) : String(format: "%.0fKbps", mbps * 1000)
    }

    private func fps() -> Double? {
        guard let tracks = player.media?.tracksInformation as? [[String: Any]] else { return nil }
        for t in tracks where (t[VLCMediaTracksInformationType] as? String) == VLCMediaTracksInformationTypeVideo {
            if let num = t[VLCMediaTracksInformationFrameRate] as? NSNumber,
               let den = t[VLCMediaTracksInformationFrameRateDenominator] as? NSNumber, den.doubleValue > 0 {
                return num.doubleValue / den.doubleValue
            }
        }
        return nil
    }

    private func saveProgress() {
        guard !request.live, let length = player.media?.length.intValue, length > 0 else { return }
        IosPlayback.shared.saveProgress(posMs: Int64(player.time.intValue), durMs: Int64(length))
    }

    private func format(_ ms: Int) -> String {
        let s = ms / 1000
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%02d:%02d", s / 60, s % 60)
    }

    // MARK: Guide and channel lists

    private func loadGuide() {
        guideStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        guard request.live else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            let rows = IosPlayback.shared.guide()
            DispatchQueue.main.async { self.showGuide(rows) }
        }
    }

    private func showGuide(_ rows: [GuideRow]) {
        guideStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        if rows.isEmpty {
            let icon = UIImageView(image: UIImage(systemName: "list.bullet.rectangle", withConfiguration: UIImage.SymbolConfiguration(pointSize: 40)))
            icon.tintColor = Palette.dim
            icon.contentMode = .center
            let l = UILabel(); label(l, size: 20, weight: .bold); l.text = "No EPG was found"; l.textAlignment = .center
            let empty = UIStackView(arrangedSubviews: [icon, l])
            empty.axis = .vertical; empty.spacing = 14
            empty.isLayoutMarginsRelativeArrangement = true
            empty.layoutMargins = UIEdgeInsets(top: 40, left: 0, bottom: 40, right: 0)
            guideStack.addArrangedSubview(empty)
            return
        }
        for r in rows.prefix(30) {
            let time = UILabel(); label(time, size: 14, weight: .semibold, color: r.live ? Palette.accent : Palette.dim)
            time.text = r.times
            let title = UILabel(); label(title, size: 16, weight: r.live ? .bold : .medium, lines: 2)
            title.text = r.title
            let stack = UIStackView(arrangedSubviews: [time, title])
            stack.axis = .vertical; stack.spacing = 3
            stack.isLayoutMarginsRelativeArrangement = true
            stack.layoutMargins = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
            if r.live { stack.backgroundColor = Palette.panel; stack.layer.cornerRadius = 12 }
            guideStack.addArrangedSubview(stack)
        }
    }

    private func loadMore() {
        moreChannels = []
        moreCards.reloadData()
        guard request.live else { moreTitle.isHidden = true; moreCards.isHidden = true; return }
        moreTitle.isHidden = false; moreCards.isHidden = false
        moreTitle.text = "More from \"\(request.categoryName)\""
        let cat = request.categoryId
        DispatchQueue.global(qos: .userInitiated).async {
            let list = IosPlayback.shared.channelsIn(categoryId: cat, withGuide: 30)
            DispatchQueue.main.async {
                self.moreChannels = list
                self.moreCards.reloadData()
                if let i = list.firstIndex(where: { $0.playing }) {
                    self.moreCards.scrollToItem(at: IndexPath(item: i, section: 0), at: .centeredHorizontally, animated: false)
                }
            }
        }
    }

    @objc private func openDrawer() {
        drawer.isHidden = false
        controls.isHidden = true
        hideTimer?.invalidate()
        DispatchQueue.global(qos: .userInitiated).async {
            let cats = IosPlayback.shared.categories()
            DispatchQueue.main.async {
                self.categories = cats
                self.showTabs()
                self.selectCategory(self.request.categoryId.isEmpty ? (cats.first?.id ?? "") : self.request.categoryId)
            }
        }
    }

    @objc private func closeDrawer() {
        drawer.isHidden = true
        showControls()
    }

    private func showTabs() {
        tabsStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (i, c) in categories.enumerated() {
            let b = UIButton(type: .system)
            b.setTitle(c.name, for: .normal)
            b.titleLabel?.font = .systemFont(ofSize: 17, weight: .bold)
            b.contentEdgeInsets = UIEdgeInsets(top: 8, left: 20, bottom: 8, right: 20)
            b.layer.cornerRadius = 23
            b.tag = i
            b.addTarget(self, action: #selector(tabTapped(_:)), for: .touchUpInside)
            tabsStack.addArrangedSubview(b)
        }
    }

    @objc private func tabTapped(_ b: UIButton) { selectCategory(categories[b.tag].id) }

    private func selectCategory(_ id: String) {
        drawerCategory = id
        for case let b as UIButton in tabsStack.arrangedSubviews {
            let on = categories[b.tag].id == id
            b.backgroundColor = on ? .white : UIColor(white: 1, alpha: 0.14)
            b.setTitleColor(on ? Palette.live : .white, for: .normal)
            if on { DispatchQueue.main.async { (self.tabsStack.superview as? UIScrollView)?.scrollRectToVisible(b.frame.insetBy(dx: -80, dy: 0), animated: true) } }
        }
        drawerChannels = []
        drawerCards.reloadData()
        DispatchQueue.global(qos: .userInitiated).async {
            let list = IosPlayback.shared.channelsIn(categoryId: id, withGuide: 20)
            DispatchQueue.main.async {
                guard self.drawerCategory == id else { return }
                self.drawerChannels = list
                self.drawerCards.reloadData()
            }
        }
    }

    func collectionView(_ cv: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        cv === drawerCards ? drawerChannels.count : moreChannels.count
    }

    func collectionView(_ cv: UICollectionView, cellForItemAt ip: IndexPath) -> UICollectionViewCell {
        let cell = cv.dequeueReusableCell(withReuseIdentifier: "c", for: ip) as! ChannelCell
        cell.show(cv === drawerCards ? drawerChannels[ip.item] : moreChannels[ip.item])
        return cell
    }

    func collectionView(_ cv: UICollectionView, didSelectItemAt ip: IndexPath) {
        let drawerPick = cv === drawerCards
        let c = drawerPick ? drawerChannels[ip.item] : moreChannels[ip.item]
        let cat = drawerPick ? drawerCategory : request.categoryId
        if drawerPick { closeDrawer() }
        spinner.startAnimating()
        IosPlayback.shared.playIn(categoryId: cat, channelId: c.id)
    }

    // MARK: Controls

    /// Whether the controls are meant to be on screen; taps toggle it (a fade in progress must not undo a tap).
    private var controlsShown = true

    private func showControls(stay: Bool = false) {
        guard drawer.isHidden else { return }
        controlsShown = true
        controls.layer.removeAllAnimations()
        controls.isHidden = false
        controls.alpha = 1
        if locked { setLockedLook(); return }
        hideTimer?.invalidate()
        if !stay { hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in self?.hideControls() } }
    }

    private func hideControls() {
        hideTimer?.invalidate()
        controlsShown = false
        UIView.animate(withDuration: 0.25) { self.controls.alpha = 0 } completion: { _ in
            if !self.controlsShown { self.controls.isHidden = true }
        }
    }

    /// Only taps on the picture itself toggle the controls (not the page below it in portrait, nor any button).
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        var v = touch.view
        while let cur = v {
            if cur is UIControl || cur is UICollectionView || cur is VerticalSlider || cur === page { return false }
            if cur === videoBox { return true }
            v = cur.superview
        }
        return false
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    @objc private func tapped() {
        if !drawer.isHidden { closeDrawer(); return }
        if controlsShown { hideControls() } else { showControls() }
    }

    @objc private func close() {
        // The full-screen player steps back to the channel page first, as IM Player does.
        if isLandscape && request.live { setOrientation(.portrait) } else { dismiss(animated: true) }
    }

    @objc private func togglePlay() { if player.isPlaying { player.pause() } else { player.play() }; updatePlayIcon(); showControls() }
    @objc private func prevChannel() { spinner.startAnimating(); IosPlayback.shared.zap(delta: -1); showControls() }
    @objc private func nextChannel() { spinner.startAnimating(); IosPlayback.shared.zap(delta: 1); showControls() }

    @objc private func skipBack() { player.jumpBackward(15); showControls() }
    @objc private func skipForward() { player.jumpForward(15); showControls() }

    @objc private func rotate() { setOrientation(isLandscape ? .portrait : .landscapeRight) }

    private func setOrientation(_ mask: UIInterfaceOrientationMask) {
        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
            view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
        } else {
            let o: UIInterfaceOrientation = mask == .portrait ? .portrait : .landscapeRight
            UIDevice.current.setValue(o.rawValue, forKey: "orientation")
        }
    }

    private func setFavourite(_ on: Bool) {
        favButton.setImage(UIImage(systemName: on ? "heart.fill" : "heart", withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .semibold)), for: .normal)
        favButton.tintColor = on ? Palette.live : .white
    }

    @objc private func toggleFavourite() { setFavourite(IosPlayback.shared.toggleFavourite()); showControls() }

    @objc private func toggleLock() {
        locked.toggle()
        lockButton.setImage(UIImage(systemName: locked ? "lock.fill" : "lock.open.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .semibold)), for: .normal)
        if locked { setLockedLook() } else { for v in controls.subviews { v.isHidden = false }; applyLayout() }
    }

    /// Locked: only the lock button shows, and the screen ignores taps until it is unlocked.
    private func setLockedLook() {
        hideTimer?.invalidate()
        for v in controls.subviews { v.isHidden = v !== lockButton }
    }

    @objc private func showInfo() {
        let size = player.videoSize
        var lines = [request.live ? request.channelName : request.title]
        if request.live { lines.append(request.nowTitle) }
        if size.width > 0 { lines.append("Picture: \(Int(size.width)) x \(Int(size.height))") }
        if let f = fps() { lines.append(String(format: "Frame rate: %.0f FPS", f)) }
        lines.append("Stream: \(URL(string: request.url)?.host ?? "")")
        let a = UIAlertController(title: "Stream information", message: lines.joined(separator: "\n"), preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default))
        present(a, animated: true)
    }

    /// Subtitles, audio and video tracks, and the picture's shape.
    private func settingsMenu() -> [UIMenuElement] {
        func tracks(_ title: String, _ icon: String, names: [Any]?, ids: [Any]?, current: Int32, pick: @escaping (Int32) -> Void) -> UIMenu {
            let n = (names as? [String]) ?? []
            let i = (ids as? [NSNumber]) ?? []
            let actions = zip(n, i).map { name, id in
                UIAction(title: name, state: id.int32Value == current ? .on : .off) { _ in pick(id.int32Value) }
            }
            return UIMenu(title: title, image: UIImage(systemName: icon), children: actions.isEmpty ? [UIAction(title: "None", attributes: .disabled) { _ in }] : actions)
        }
        let p = player
        let ratios: [(String, String?)] = [("Default", nil), ("16:9", "16:9"), ("4:3", "4:3"), ("21:9", "21:9"), ("Fill screen", "fill")]
        let aspectMenu = UIMenu(title: "Aspect Ratio", image: UIImage(systemName: "aspectratio"), children: ratios.map { name, value in
            UIAction(title: name, state: self.aspect == value ? .on : .off) { [weak self] _ in self?.setAspect(value) }
        })
        return [
            tracks("Subtitles Tracks", "captions.bubble", names: p.videoSubTitlesNames, ids: p.videoSubTitlesIndexes, current: p.currentVideoSubTitleIndex) { p.currentVideoSubTitleIndex = $0 },
            tracks("Audio Tracks", "waveform", names: p.audioTrackNames, ids: p.audioTrackIndexes, current: p.currentAudioTrackIndex) { p.currentAudioTrackIndex = $0 },
            tracks("Video Tracks", "video", names: p.videoTrackNames, ids: p.videoTrackIndexes, current: p.currentVideoTrackIndex) { p.currentVideoTrackIndex = $0 },
            UIMenu(title: "Playback Speed", image: UIImage(systemName: "speedometer"), children: [0.5, 0.75, 1, 1.25, 1.5, 2].map { r in
                UIAction(title: "\(r)×", state: abs(Double(p.rate) - r) < 0.01 ? .on : .off) { _ in p.rate = Float(r) }
            }),
            aspectMenu,
        ]
    }

    private func setAspect(_ value: String?) {
        aspect = value
        if value == "fill" {
            let b = videoBox.bounds.size
            let r = "\(Int(b.width)):\(Int(b.height))"
            player.videoAspectRatio = UnsafeMutablePointer<CChar>(mutating: (r as NSString).utf8String)
        } else if let v = value {
            player.videoAspectRatio = UnsafeMutablePointer<CChar>(mutating: (v as NSString).utf8String)
        } else {
            player.videoAspectRatio = nil
        }
    }

    @objc private func seekStart() { seeking = true; hideTimer?.invalidate() }
    @objc private func seekEnd() {
        if let length = player.media?.length.intValue, length > 0 {
            player.time = VLCTime(int: Int32(clamping: Int(Float(length) * timeline.value)))
        }
        seeking = false
        showControls()
    }
}

/// A channel in the card rows: logo, name, what is on now.
final class ChannelCell: UICollectionViewCell {
    private let logo = UIImageView()
    private let name = UILabel()
    private let now = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = UIColor(white: 0.12, alpha: 0.92)
        contentView.layer.cornerRadius = 18
        contentView.clipsToBounds = true
        logo.contentMode = .scaleAspectFit
        name.font = .systemFont(ofSize: 16, weight: .bold); name.textColor = .white; name.textAlignment = .center
        now.font = .systemFont(ofSize: 13); now.textColor = Palette.dim; now.textAlignment = .center
        let stack = UIStackView(arrangedSubviews: [logo, name, now])
        stack.axis = .vertical; stack.spacing = 8; stack.alignment = .fill
        logo.heightAnchor.constraint(equalToConstant: 64).isActive = true
        contentView.pin(stack, insets: UIEdgeInsets(top: 16, left: 12, bottom: 14, right: 12))
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func show(_ c: ChannelCard) {
        name.text = c.name
        now.text = c.now.isEmpty ? "No information" : c.now
        contentView.layer.borderWidth = c.playing ? 2 : 0
        contentView.layer.borderColor = Palette.accent.cgColor
        Images.shared.load(c.logo, into: logo)
    }
}

/// A tall frosted capsule that fills from the bottom: brightness on the left, volume on the right.
final class VerticalSlider: UIView {
    var onChange: ((Float) -> Void)?
    var value: Float = 0.5 { didSet { setNeedsLayout() } }
    var symbol: String { didSet { icon.image = UIImage(systemName: symbol) } }
    private let fill = UIView()
    private let icon = UIImageView()

    init(symbol: String) {
        self.symbol = symbol
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: 64).isActive = true
        heightAnchor.constraint(equalToConstant: 170).isActive = true
        layer.cornerRadius = 32
        clipsToBounds = true
        _ = glass(radius: 32)
        fill.backgroundColor = .white
        addSubview(fill)
        icon.image = UIImage(systemName: symbol)
        icon.tintColor = UIColor(red: 1, green: 0.78, blue: 0.1, alpha: 1)
        icon.contentMode = .scaleAspectFit
        addSubview(icon)
        addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(pan(_:))))
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let h = bounds.height * CGFloat(max(0, min(1, value)))
        fill.frame = CGRect(x: 0, y: bounds.height - h, width: bounds.width, height: h)
        icon.frame = CGRect(x: (bounds.width - 30) / 2, y: bounds.height - 46, width: 30, height: 30)
        icon.tintColor = value > 0.2 ? UIColor(red: 1, green: 0.78, blue: 0.1, alpha: 1) : .white
    }

    @objc private func pan(_ g: UIPanGestureRecognizer) {
        let y = g.location(in: self).y
        value = Float(max(0, min(1, 1 - y / bounds.height)))
        onChange?(value)
    }
}
