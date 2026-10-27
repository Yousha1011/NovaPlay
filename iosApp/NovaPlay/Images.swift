import UIKit

/// Channel logos from the playlist, downloaded once and kept in memory.
final class Images {
    static let shared = Images()
    private let cache = NSCache<NSString, UIImage>()
    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 15
        return URLSession(configuration: c)
    }()

    /// Loads [url] into [view], unless the view has been reused for another image meanwhile.
    func load(_ url: String?, into view: UIImageView) {
        view.image = nil
        guard let s = url, !s.isEmpty, let u = URL(string: s) else { return }
        view.accessibilityIdentifier = s
        if let img = cache.object(forKey: s as NSString) { view.image = img; return }
        session.dataTask(with: u) { [weak self, weak view] data, _, _ in
            guard let data, let img = UIImage(data: data) else { return }
            self?.cache.setObject(img, forKey: s as NSString)
            DispatchQueue.main.async { if view?.accessibilityIdentifier == s { view?.image = img } }
        }.resume()
    }
}

/// The app's colours, as in the shared screens.
enum Palette {
    static let bg = UIColor(red: 0.031, green: 0.043, blue: 0.071, alpha: 1)
    static let panel = UIColor(red: 0.078, green: 0.102, blue: 0.157, alpha: 1)
    static let panelHi = UIColor(red: 0.110, green: 0.141, blue: 0.212, alpha: 1)
    static let accent = UIColor(red: 0.357, green: 0.549, blue: 1, alpha: 1)
    static let live = UIColor(red: 1, green: 0.302, blue: 0.369, alpha: 1)
    static let dim = UIColor(red: 0.639, green: 0.671, blue: 0.741, alpha: 1)
}

extension UIView {
    /// A frosted, rounded background, like the player's floating controls.
    func glass(radius: CGFloat) -> UIVisualEffectView {
        let v = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterialDark))
        v.layer.cornerRadius = radius
        v.clipsToBounds = true
        v.isUserInteractionEnabled = false
        insertSubview(v, at: 0)
        v.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            v.leadingAnchor.constraint(equalTo: leadingAnchor), v.trailingAnchor.constraint(equalTo: trailingAnchor),
            v.topAnchor.constraint(equalTo: topAnchor), v.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        return v
    }

    func pin(_ child: UIView, insets: UIEdgeInsets = .zero) {
        child.translatesAutoresizingMaskIntoConstraints = false
        addSubview(child)
        NSLayoutConstraint.activate([
            child.leadingAnchor.constraint(equalTo: leadingAnchor, constant: insets.left),
            child.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -insets.right),
            child.topAnchor.constraint(equalTo: topAnchor, constant: insets.top),
            child.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -insets.bottom),
        ])
    }
}

/// A round icon button on frosted glass.
func roundButton(_ symbol: String, size: CGFloat = 44, point: CGFloat = 18, target: Any?, action: Selector) -> UIButton {
    let b = UIButton(type: .system)
    b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: point, weight: .semibold)), for: .normal)
    b.tintColor = .white
    b.translatesAutoresizingMaskIntoConstraints = false
    b.widthAnchor.constraint(equalToConstant: size).isActive = true
    b.heightAnchor.constraint(equalToConstant: size).isActive = true
    b.layer.cornerRadius = size / 2
    b.clipsToBounds = true
    _ = b.glass(radius: size / 2)
    b.addTarget(target, action: action, for: .touchUpInside)
    return b
}

/// A plain icon button for inside a glass bar.
func iconButton(_ symbol: String, point: CGFloat = 19, target: Any?, action: Selector) -> UIButton {
    let b = UIButton(type: .system)
    b.setImage(UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: point, weight: .semibold)), for: .normal)
    b.tintColor = .white
    b.translatesAutoresizingMaskIntoConstraints = false
    b.widthAnchor.constraint(equalToConstant: 44).isActive = true
    b.heightAnchor.constraint(equalToConstant: 40).isActive = true
    b.addTarget(target, action: action, for: .touchUpInside)
    return b
}

/// A small frosted label: resolution, frame rate, bitrate.
final class StatPill: UILabel {
    override init(frame: CGRect) {
        super.init(frame: frame)
        textColor = .white
        font = .systemFont(ofSize: 14, weight: .semibold)
        textAlignment = .center
        layer.cornerRadius = 16
        clipsToBounds = true
        backgroundColor = UIColor(white: 1, alpha: 0.14)
        translatesAutoresizingMaskIntoConstraints = false
        heightAnchor.constraint(equalToConstant: 32).isActive = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + 28, height: 32)
    }
}
