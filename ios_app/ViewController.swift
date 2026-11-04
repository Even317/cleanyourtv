import UIKit
import WebKit

class ViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate {
    private var webView: WKWebView!
    private let hapticGenerator = UIImpactFeedbackGenerator(style: .medium)

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return .lightContent
    }

    override var prefersStatusBarHidden: Bool {
        return true
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.05, green: 0.05, blue: 0.06, alpha: 1.0)

        hapticGenerator.prepare()

        let contentController = WKUserContentController()
        contentController.add(self, name: "haptic")

        let config = WKWebViewConfiguration()
        config.userContentController = contentController
        config.allowsInlineMediaPlayback = true

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.backgroundColor = .clear
        webView.isOpaque = false
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.navigationDelegate = self
        view.addSubview(webView)

        loadRemote()
    }

    private func loadRemote() {
        if let url = URL(string: "http://192.168.1.28:8080") {
            var request = URLRequest(url: url)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            webView.load(request)
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        if message.name == "haptic" {
            DispatchQueue.main.async {
                self.hapticGenerator.prepare()
                self.hapticGenerator.impactOccurred()
            }
        }
    }
}
