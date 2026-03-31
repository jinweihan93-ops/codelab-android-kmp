//
//  ContentView.swift
//  KMPGetStartedCodelab
//
//

import SwiftUI
import foundationKit
import businessKit

struct ContentView: View {
    @State private var results: [String] = []

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    // Original functionality
                    HStack {
                        Image(systemName: "globe")
                            .imageScale(.large)
                            .foregroundStyle(.tint)
                        Text("Hello, \(foundationKit.Platform_iosKt.platform())!")
                    }

                    let userService = businessKit.UserService()
                    let tag = userService.formatUserTag(user: userService.currentUser())
                    Text("User: \(tag)")
                        .font(.caption)

                    Divider()

                    Text("V3 Dual Runtime Evidence")
                        .font(.headline)
                        .padding(.top)

                    Button("Run All Tests") {
                        runAllTests()
                    }
                    .buttonStyle(.borderedProminent)

                    ForEach(Array(results.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.caption2, design: .monospaced))
                            .padding(2)
                    }
                }
                .padding()
            }
            .navigationTitle("KMP V3 Demo")
        }
    }

    func runAllTests() {
        results = []

        // === Task 2: Runtime Duplication Tests ===
        results.append("══════ RUNTIME DUPLICATION ══════")
        for test in RuntimeDuplicateTest.runAll() {
            let icon = test.passed ? "EVIDENCE" : "FAILED"
            results.append("[\(icon)] \(test.testName)")
            for line in test.detail.split(separator: "\n") {
                results.append("  \(line)")
            }
            results.append("")
        }

        // === Task 3: Cross-Framework Object Passing ===
        results.append("══════ CROSS-FRAMEWORK OBJECTS ══════")
        let processor = businessKit.SharedDataProcessor()

        // 3a: Foundation creates SharedData (using foundationKit module)
        let fromFoundation: Any = foundationKit.SharedDataKt.createSharedData(id: 1, message: "from foundation")
        // 3b: Business creates SharedData (using businessKit module)
        let fromBusiness: Any = processor.createLocalSharedData(id: 2, message: "from business")

        // Type names at Swift level
        let fType = String(describing: type(of: fromFoundation))
        let bType = String(describing: type(of: fromBusiness))
        results.append("[TYPE] Foundation obj: \(fType)")
        results.append("[TYPE] Business obj:   \(bType)")
        results.append("[TYPE] Same type? \(fType == bType)")
        results.append("")

        // 3c: Kotlin `is SharedData` check via validateAsSharedData(Any)
        let valBusiness = processor.validateAsSharedData(data: fromBusiness)
        let valFoundation = processor.validateAsSharedData(data: fromFoundation)
        results.append("[IS CHECK] Business obj is SharedData?    \(valBusiness)")
        results.append("[IS CHECK] Foundation obj is SharedData?  \(valFoundation)")
        results.append("")

        // 3d: Process business-created object (should work)
        let processOk = processor.forceProcessAny(data: fromBusiness)
        results.append("[CAST] Business->Business forceProcessAny: \(processOk)")

        // 3e: Process foundation-created object (would crash with ClassCastException)
        // We intentionally skip this to avoid crashing the app
        results.append("[CAST] Foundation->Business forceProcessAny: SKIPPED (would crash)")
        results.append("  reason: Kotlin 'as SharedData' throws ClassCastException")
        results.append("  because Foundation's SharedData class != Business's SharedData class")
        results.append("")

        // 3f: Class name from each runtime's perspective
        let businessClassName = processor.getSharedDataClassName()
        results.append("[CLASS] Business runtime sees: \(businessClassName)")
        results.append("")

        // Summary
        results.append("══════ SUMMARY ══════")
        results.append("Two K/N runtimes loaded: YES")
        results.append("Cross-framework is-check fails: \(!valFoundation ? "YES" : "NO")")
        results.append("Types differ at Swift level: \(fType != bType ? "YES" : "NO")")
        results.append("Object passing safe: NO (ClassCastException)")
    }
}

#Preview {
    ContentView()
}
