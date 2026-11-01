import UIKit
import MobileVLCKit
import Shared

/// Multi-view: two to four channels of the list playing, each with its own VLC player, in a grid. A tap picks the
/// screen with sound; a double tap watches it full screen. Every screen reconnects on its own when its stream drops or
/// its picture stops moving.
final class MultiViewController: UIViewController {
    private final class Screen {
        let view = UIView()
        let video = UIView()
        let name = UILabel()
        let note = UILabel()
        let spinner = UIActivityIndicatorView(style: .medium)
        let player = VLCMediaPlayer()
        var channel: Int
        var failures = 0
        /// When it was (re)opened or last moved on, for the stall check.
        var movedAt = Date()
        var lastTime: Int32 = -1
        var retryAt: Date?
        init(channel: Int) { self.channel = channel }
    }

    private var screens: [Screen] = []
    private var active = 0
    private let grid = UIView()
    private let bar = UIStackView()
    private lazy var addButton = roundButton("plus", target: self, action: #selector(addScreen))
    private var channels: [ChannelCard] = []
    private var timer: Timer?
    private let first: Int
    private let onExit: (Int) -> Void

    init(first: Int, onExit: @escaping (Int) -> Void) {
        self.first = first
        self.onExit = onExit
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        overrideUserInterfaceStyle = .dark
        channels = IosPlayback.shared.multiChannels()

        grid.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(grid)
        let buttons: [UIButton] = [
            roundButton("xmark", target: self, action: #selector(close)),
            addButton,
            roundButton("list.bullet", target: self, action: #selector(changeChannel)),
            roundButton("minus", target: self, action: #selector(removeScreen)),
            roundButton("arrow.up.left.and.arrow.down.right", target: self, action: #selector(fullScreen)),
        ]
        buttons.forEach { bar.addArrangedSubview($0) }
        bar.axis = .horizontal
        bar.spacing = 14
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)
        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            grid.topAnchor.constraint(equalTo: g.topAnchor, constant: 6),
            grid.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 6),
            grid.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -6),
            grid.bottomAnchor.constraint(equalTo: bar.topAnchor, constant: -8),
            bar.centerXAnchor.constraint(equalTo: g.centerXAnchor),
            bar.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -6),
        ])

        let count = max(channels.count, 1)
        add(channel: first)
        add(channel: (first + 1) % count)
        Orientation.landscape()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.watchdog() }
        // The second screen starts on the next channel; the list opens to pick the one wanted.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.pick(for: 1) }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layoutGrid()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        timer?.invalidate()
        screens.forEach { $0.player.stop() }
    }

    // MARK: Screens

    private func add(channel: Int) {
        guard screens.count < Int(IosPlayback.shared.maxScreens()) else { return }
        let s = Screen(channel: channel)
        s.view.backgroundColor = UIColor(white: 0.05, alpha: 1)
        s.view.layer.cornerRadius = 10
        s.view.clipsToBounds = true
        s.view.pin(s.video)
        s.video.isUserInteractionEnabled = false
        s.name.font = .systemFont(ofSize: 14, weight: .semibold)
        s.name.textColor = .white
        s.name.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        s.name.translatesAutoresizingMaskIntoConstraints = false
        s.view.addSubview(s.name)
        s.note.font = .systemFont(ofSize: 14, weight: .semibold)
        s.note.textColor = .white
        s.note.isHidden = true
        s.note.translatesAutoresizingMaskIntoConstraints = false
        s.view.addSubview(s.note)
        s.spinner.color = .white
        s.spinner.translatesAutoresizingMaskIntoConstraints = false
        s.view.addSubview(s.spinner)
        NSLayoutConstraint.activate([
            s.name.leadingAnchor.constraint(equalTo: s.view.leadingAnchor),
            s.name.trailingAnchor.constraint(equalTo: s.view.trailingAnchor),
            s.name.bottomAnchor.constraint(equalTo: s.view.bottomAnchor),
            s.name.heightAnchor.constraint(equalToConstant: 26),
            s.note.centerXAnchor.constraint(equalTo: s.view.centerXAnchor),
            s.note.centerYAnchor.constraint(equalTo: s.view.centerYAnchor),
            s.spinner.centerXAnchor.constraint(equalTo: s.view.centerXAnchor),
            s.spinner.centerYAnchor.constraint(equalTo: s.view.centerYAnchor),
        ])
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        tap.require(toFail: double)
        s.view.addGestureRecognizer(tap)
        s.view.addGestureRecognizer(double)
        s.player.drawable = s.video
        grid.addSubview(s.view)
        screens.append(s)
        open(s)
        setSound(screens.count - 1)
        layoutGrid()
        addButton.isEnabled = screens.count < Int(IosPlayback.shared.maxScreens())
    }

    /// Opens (or reopens) a screen's stream; the address is fetched off the main queue.
    private func open(_ s: Screen) {
        s.retryAt = nil
        s.movedAt = Date()
        s.lastTime = -1
        s.spinner.startAnimating()
        let name = channels.indices.contains(s.channel) ? channels[s.channel].name : ""
        s.name.text = "  " + name
        let position = Int32(s.channel)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let url = IosPlayback.shared.multiUrl(position: position)
            let hd = IosPlayback.shared.multiIsHd(position: position)
            DispatchQueue.main.async {
                s.name.text = "  " + name + (hd ? "  ·  HD" : "")
                guard let self = self, self.screens.contains(where: { $0 === s }) else { return }
                guard let u = url, let link = URL(string: u) else { self.dropped(s); return }
                let media = VLCMedia(url: link)
                media.addOption(":http-user-agent=\(IosPlayback.shared.userAgent())")
                media.addOption(":network-caching=\(max(1000, min(15000, Int(IosPlayback.shared.bufferMs()))))")
                media.addOption(":http-reconnect")
                s.player.stop()
                s.player.media = media
                s.player.play()
            }
        }
    }

    private func dropped(_ s: Screen) {
        s.failures += 1
        if s.failures == Int(IosPlayback.shared.warnAfter()) { warnLimit() }
        s.note.text = "Reconnecting…"
        s.note.isHidden = false
        s.player.stop()
        s.retryAt = Date().addingTimeInterval(Double(IosPlayback.shared.retryDelayMs(failures: Int32(s.failures))) / 1000)
    }

    /// Once a second: a stream that failed or ended reconnects after a short wait; one whose picture hasn't moved for
    /// a while is opened again.
    private func watchdog() {
        let now = Date()
        let stall = Double(IosPlayback.shared.stallMs()) / 1000
        for s in screens {
            if let at = s.retryAt { if now >= at { open(s) }; continue }
            // A stream that failed or ended (given a few seconds to get going after opening).
            if now.timeIntervalSince(s.movedAt) > 4 {
                switch s.player.state {
                case .error, .ended, .stopped:
                    dropped(s); continue
                default: break
                }
            }
            let t = s.player.time.intValue
            if s.player.isPlaying && t != s.lastTime {
                s.lastTime = t
                s.movedAt = now
                s.failures = 0
                s.note.isHidden = true
                s.spinner.stopAnimating()
            } else if now.timeIntervalSince(s.movedAt) > stall {
                s.note.text = "Reconnecting…"
                s.note.isHidden = false
                open(s)
            }
        }
    }

    private var limitWarned = false

    /// A screen keeps failing: when the playlist's stream limit may be why, say so (once).
    private func warnLimit() {
        if limitWarned { return }
        limitWarned = true
        let n = Int32(screens.count)
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let msg = IosPlayback.shared.multiLimitWarning(screens: n) else { return }
            DispatchQueue.main.async {
                let a = UIAlertController(title: nil, message: msg, preferredStyle: .alert)
                a.addAction(UIAlertAction(title: "OK", style: .default))
                self?.present(a, animated: true)
            }
        }
    }

    /// Sound follows the chosen screen (with a cyan frame); the others play muted.
    private func setSound(_ i: Int) {
        active = max(0, min(i, screens.count - 1))
        for (k, s) in screens.enumerated() {
            s.player.audio?.isMuted = k != active
            s.view.layer.borderWidth = k == active ? 3 : 1
            s.view.layer.borderColor = (k == active ? Palette.accent : UIColor(white: 1, alpha: 0.15)).cgColor
        }
    }

    private func layoutGrid() {
        let n = screens.count
        guard n > 0 else { return }
        let cols = n <= 1 ? 1 : 2
        let rows = n <= 2 ? 1 : 2
        let gap: CGFloat = 8
        let b = grid.bounds
        // Each screen is 16:9, as big as fits.
        var w = (b.width - gap * CGFloat(cols - 1)) / CGFloat(cols)
        var h = w * 9 / 16
        let maxH = (b.height - gap * CGFloat(rows - 1)) / CGFloat(rows)
        if h > maxH { h = maxH; w = h * 16 / 9 }
        let totalW = w * CGFloat(cols) + gap * CGFloat(cols - 1)
        let totalH = h * CGFloat(rows) + gap * CGFloat(rows - 1)
        for (i, s) in screens.enumerated() {
            let r = i / cols, c = i % cols
            // A third screen on its own sits in the middle of the bottom row.
            let rowCount = min(cols, n - r * cols)
            let rowW = w * CGFloat(rowCount) + gap * CGFloat(rowCount - 1)
            let x = (b.width - rowW) / 2 + CGFloat(c) * (w + gap)
            let y = (b.height - totalH) / 2 + CGFloat(r) * (h + gap)
            _ = totalW
            s.view.frame = CGRect(x: x, y: y, width: w, height: h)
        }
    }

    // MARK: Actions

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        if let i = screens.firstIndex(where: { $0.view === g.view }) { setSound(i) }
    }

    @objc private func doubleTapped(_ g: UITapGestureRecognizer) {
        if let i = screens.firstIndex(where: { $0.view === g.view }) { finish(with: screens[i].channel) }
    }

    @objc private func addScreen() { choose(for: -1) }
    @objc private func changeChannel() { choose(for: active) }

    /// Live TV (the channel list) or Sports (a game, then the channels showing it).
    private func choose(for target: Int) {
        let a = UIAlertController(title: target < 0 ? "Add a screen" : "Change this screen",
                                  message: "Pick a channel, or a game and the channel showing it", preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Live TV", style: .default) { [weak self] _ in self?.pick(for: target) })
        a.addAction(UIAlertAction(title: "Sports", style: .default) { [weak self] _ in self?.pickGame(for: target) })
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        a.popoverPresentationController?.sourceView = bar
        present(a, animated: true)
    }

    private func pickGame(for target: Int) {
        let list = GamePickerController { [weak self] channelId in
            guard let self = self else { return }
            let position = Int(IosPlayback.shared.multiAddGameChannel(channelId: channelId))
            guard position >= 0 else { return }
            self.channels = IosPlayback.shared.multiChannels()
            self.place(position, for: target)
        }
        let nav = UINavigationController(rootViewController: list)
        nav.overrideUserInterfaceStyle = .dark
        nav.modalPresentationStyle = .formSheet
        present(nav, animated: true)
    }

    /// Channel [position] goes on screen [target], or on a new screen with -1.
    private func place(_ position: Int, for target: Int) {
        if target < 0 { add(channel: position) }
        else if target < screens.count {
            let s = screens[target]
            s.channel = position
            s.failures = 0
            open(s)
        }
    }
    @objc private func fullScreen() { finish(with: screens[active].channel) }
    @objc private func close() { finish(with: screens[active].channel) }

    @objc private func removeScreen() {
        if screens.count <= 2 { finish(with: screens[active == 0 ? 1 : 0].channel); return }
        let s = screens.remove(at: active)
        s.player.stop()
        s.view.removeFromSuperview()
        setSound(min(active, screens.count - 1))
        addButton.isEnabled = true
        layoutGrid()
    }

    private func finish(with channel: Int) {
        timer?.invalidate()
        screens.forEach { $0.player.stop() }
        dismiss(animated: true) { [onExit] in onExit(channel) }
    }

    /// The channel list, for screen [target] or (with -1) a new screen.
    private func pick(for target: Int) {
        let current = target >= 0 && target < screens.count ? screens[target].channel : -1
        let list = ChannelPickerController(channels: channels, current: current,
                                           title: target < 0 ? "Add a channel" : "Channel for this screen") { [weak self] position in
            guard let self = self else { return }
            if target < 0 { self.add(channel: position) }
            else if target < self.screens.count {
                let s = self.screens[target]
                s.channel = position
                s.failures = 0
                self.open(s)
            }
        }
        let nav = UINavigationController(rootViewController: list)
        nav.overrideUserInterfaceStyle = .dark
        nav.modalPresentationStyle = .formSheet
        present(nav, animated: true)
    }
}

/// A plain list of the channels to pick from.
final class ChannelPickerController: UITableViewController {
    private let channels: [ChannelCard]
    private let current: Int
    private let picked: (Int) -> Void

    init(channels: [ChannelCard], current: Int, title: String, picked: @escaping (Int) -> Void) {
        self.channels = channels
        self.current = current
        self.picked = picked
        super.init(style: .plain)
        self.title = title
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "c")
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if channels.indices.contains(current) { tableView.scrollToRow(at: IndexPath(row: current, section: 0), at: .middle, animated: false) }
    }

    @objc private func cancel() { dismiss(animated: true) }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { channels.count }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "c", for: indexPath)
        var c = cell.defaultContentConfiguration()
        c.text = channels[indexPath.row].name
        cell.contentConfiguration = c
        cell.accessoryType = indexPath.row == current ? .checkmark : .none
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let row = indexPath.row
        dismiss(animated: true) { [picked] in picked(row) }
    }
}

/// Games on now and soon; choosing one lists the channels showing it, and choosing a channel hands back its id.
final class GamePickerController: UITableViewController {
    private var games: [GameRow] = []
    private var channels: [ChannelCard] = []
    private var game: GameRow?
    private let picked: (String) -> Void

    init(picked: @escaping (String) -> Void) {
        self.picked = picked
        super.init(style: .plain)
        title = "Pick a game"
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "c")
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
        games = IosPlayback.shared.multiGames()
        tableView.reloadData()
    }

    @objc private func cancel() {
        if game != nil { game = nil; title = "Pick a game"; tableView.reloadData() } else { dismiss(animated: true) }
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { game == nil ? games.count : channels.count }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "c", for: indexPath)
        var c = UIListContentConfiguration.subtitleCell()
        if game == nil {
            c.text = games[indexPath.row].title
            c.secondaryText = games[indexPath.row].detail
        } else {
            c.text = channels[indexPath.row].name
        }
        cell.contentConfiguration = c
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if game == nil {
            let g = games[indexPath.row]
            game = g
            title = g.title
            channels = []
            tableView.reloadData()
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                let list = IosPlayback.shared.multiGameChannels(gameId: g.id)
                DispatchQueue.main.async { self?.channels = list; self?.tableView.reloadData() }
            }
        } else {
            let id = channels[indexPath.row].id
            dismiss(animated: true) { [picked] in picked(id) }
        }
    }
}
