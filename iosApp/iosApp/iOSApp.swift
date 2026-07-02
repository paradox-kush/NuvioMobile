import SwiftUI
import ComposeApp
import PostHog

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    init() {
        // Public client-side key — safe to ship in the binary.
        let config = PostHogConfig(
            projectToken: "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye",
            host: "https://us.i.posthog.com"
        )
        // Capture crashes (Mach exceptions, POSIX signals, NSExceptions) as $exception events.
        config.errorTrackingConfig.autoCapture = true
        PostHogSDK.shared.setup(config)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                }
        }
    }
}
