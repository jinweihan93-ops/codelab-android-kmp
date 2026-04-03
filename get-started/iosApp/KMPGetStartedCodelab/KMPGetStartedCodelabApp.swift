//
//  KMPGetStartedCodelabApp.swift
//  KMPGetStartedCodelab
//

import SwiftUI
import foundationKit
import businessKit

@main
struct KMPGetStartedCodelabApp: App {

    init() {
        setupBridges()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }

    // ─── Bridge setup ──────────────────────────────────────────────────────────
    // Register host-app implementations of the cinterop bridge protocols so that
    // Kotlin code in foundationKit / businessKit can call into iOS capabilities.
    // This must happen before any Kotlin code executes.

    private func setupBridges() {
        // Foundation: platform info + logging
        BridgeSetupKt.configureFoundationBridge(provider: AppPlatformProvider())
        BridgeSetupKt.configureFoundationLogger(delegate: AppLoggerDelegate())

        // Business: auth + network
        BusinessBridgeSetupKt.configureBusinessBridge(
            authDelegate: AppAuthDelegate(),
            networkDelegate: AppNetworkDelegate()
        )
    }
}
