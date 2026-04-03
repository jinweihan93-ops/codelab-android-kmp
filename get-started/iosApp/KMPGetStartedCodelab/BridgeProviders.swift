//
//  BridgeProviders.swift
//  KMPGetStartedCodelab
//
//  Concrete implementations of the cinterop bridge protocols.
//  Each class conforms to an ObjC protocol declared in the bridge headers
//  (KMPFoundationBridge.h, KMPBusinessBridgeAuth.h, etc.) and exposed
//  via the foundationKit / businessKit umbrella headers.
//
//  These are registered at startup in KMPGetStartedCodelabApp.setupBridges().
//

import UIKit
import foundationKit
import businessKit

// ─── Foundation: Platform Info ────────────────────────────────────────────────

/// Provides device and app metadata to foundationKit's Kotlin code.
@objc final class AppPlatformProvider: NSObject, KMPPlatformInfoProvider {

    func osVersion() -> String {
        UIDevice.current.systemVersion
    }

    func deviceModel() -> String {
        // Map the raw identifier to a readable model name in a real app.
        var systemInfo = utsname()
        uname(&systemInfo)
        return withUnsafeBytes(of: &systemInfo.machine) { ptr -> String in
            String(cString: ptr.baseAddress!.assumingMemoryBound(to: CChar.self))
        }
    }

    func appVersion() -> String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    func isDebugMode() -> Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
}

// ─── Foundation: Logger ───────────────────────────────────────────────────────

/// Forwards Kotlin log records to NSLog (swap for os_log / CocoaLumberjack in production).
@objc final class AppLoggerDelegate: NSObject, KMPLoggerDelegate {

    func logMessage(_ message: String, level: KMPLogLevel, tag: String) {
        let prefix: String
        switch level {
        case .verbose:  prefix = "V"
        case .debug:    prefix = "D"
        case .info:     prefix = "I"
        case .warning:  prefix = "W"
        case .error:    prefix = "E"
        @unknown default: prefix = "?"
        }
        NSLog("[KMP/%@][%@] %@", prefix, tag, message)
    }
}

// ─── Business: Auth ───────────────────────────────────────────────────────────

/// Stub auth delegate — returns demo values.
/// In production, forward to your account/session manager.
@objc final class AppAuthDelegate: NSObject, KMPAuthDelegate {

    func currentUserId() -> String? { "demo-user-001" }

    func isAuthenticated() -> Bool { true }

    func authToken() -> String? { "demo-bearer-token-\(Int.random(in: 1000...9999))" }
}

// ─── Business: Network ────────────────────────────────────────────────────────

/// Stub network delegate — always reports Wi-Fi and returns mock responses.
/// In production, delegate to URLSession with your app's configuration.
@objc final class AppNetworkDelegate: NSObject, KMPNetworkDelegate {

    func reachability() -> KMPNetworkReachability { .wiFi }

    func requestURL(
        _ url: String,
        method: String,
        body: String?,
        completion: @escaping (String?, Int, String?) -> Void
    ) {
        // Simulate a short async round-trip with a stub JSON response.
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) {
            let stub = #"{"status":"ok","url":"\#(url)","method":"\#(method)"}"#
            completion(stub, 200, nil)
        }
    }
}
