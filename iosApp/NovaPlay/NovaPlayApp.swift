import SwiftUI
import UIKit
import Shared

/// The iPhone app: the shared NovaPlay screens full screen, and VLC's player over them for live TV and films.
@main
struct NovaPlayApp: SwiftUI.App {
    init() {
        IosEntryKt.start()
        PlayerHost.shared.connect()
    }

    var body: some Scene {
        WindowGroup {
            ComposeScreen()
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
                .statusBarHidden(true)
        }
    }
}

/// Turns the screen on request (the player goes landscape for films).
enum Orientation {
    static func landscape() {
        DispatchQueue.main.async {
            guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
            if #available(iOS 16.0, *) {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
            } else {
                UIDevice.current.setValue(UIInterfaceOrientation.landscapeRight.rawValue, forKey: "orientation")
            }
        }
    }
}

/// The shared Compose UI as a SwiftUI view.
struct ComposeScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { IosEntryKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Shows the player when the shared code asks for it, and switches its stream when the user zaps.
final class PlayerHost {
    static let shared = PlayerHost()
    private weak var current: PlayerViewController?

    func connect() {
        IosPlayback.shared.presenter = { [weak self] request in self?.show(request) }
        IosPlayback.shared.failed = { [weak self] message in
            if let player = self?.current { player.showError(message) } else { self?.alert(message) }
        }
    }

    private func topController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }

    private func show(_ request: PlayRequest) {
        if let player = current {
            player.load(request)
            return
        }
        let player = PlayerViewController(request: request)
        player.modalPresentationStyle = .fullScreen
        current = player
        topController()?.present(player, animated: true) {
            // Films and episodes play full screen; live channels open on their page upright, as in IM Player.
            if !request.live { Orientation.landscape() }
        }
    }

    private func alert(_ message: String) {
        let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default))
        topController()?.present(a, animated: true)
    }
}
