//
//  ContentView.swift
//  KMPGetStartedCodelab
//
//

import SwiftUI
import foundationKit
import businessKit
import Darwin

struct ContentView: View {
    @State private var results: [String] = []
    @State private var kmt2364Status: String = "⏳ checking..."
    @State private var kmt2364Color: Color = .gray

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

                    // KMT-2364 Phase 2 status (auto-checked on appear)
                    GroupBox(label: Text("KMT-2364 externalKlibs Fix").font(.headline)) {
                        Text(kmt2364Status)
                            .font(.system(.body, design: .monospaced))
                            .foregroundColor(kmt2364Color)
                    }
                    .onAppear { checkKmt2364() }

                    Divider()

                    Text("V3 Dual Runtime Evidence")
                        .font(.headline)
                        .padding(.top)

                    HStack {
                        Button("Run All Tests") {
                            runAllTests()
                        }
                        .buttonStyle(.borderedProminent)

                        Button("Phase 2 Tests") {
                            runPhase2Tests()
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.purple)
                    }

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

    func checkKmt2364() {
        let processor = businessKit.SharedDataProcessor()
        let fromFoundation: Any = foundationKit.SharedDataKt.createSharedData(id: 42, message: "cross-fw test")

        // Test 1: Kotlin 'is SharedData' check via Any
        let isCheck = processor.validateAsSharedData(data: fromFoundation)

        // Test 2: Use dlsym to find kclass addresses from both sides
        // Darwin: _kclass:... in nm = kclass:... in LLVM IR (dyld adds the _)
        // K/N symbol mangling: colon is allowed in LLVM but needs encoding for dyld
        // dlsym uses C-level names (no Mach-O _ prefix)
        let symName = "kclass:com.example.kmp.foundation.SharedData"
        let kclassFnd = dlsym(UnsafeMutableRawPointer(bitPattern: -2), symName)  // RTLD_DEFAULT = -2 on Darwin
        // Also look up via each framework's handle
        let fndHandle = dlopen("@rpath/foundationKit.framework/foundationKit", RTLD_NOLOAD)
        let bizHandle = dlopen("@rpath/businessKit.framework/businessKit", RTLD_NOLOAD)
        let kclassFndDirect = fndHandle != nil ? dlsym(fndHandle, symName) : nil
        let kclassBizDirect = bizHandle != nil ? dlsym(bizHandle, symName) : nil
        if fndHandle != nil { dlclose(fndHandle) }
        if bizHandle != nil { dlclose(bizHandle) }

        var castResult = ""
        if isCheck {
            let cast = processor.forceProcessAny(data: fromFoundation)
            castResult = "\n  as-cast: ✅ \(cast)"
        }

        let fndAddr = kclassFnd.map { String(format: "0x%014llx", UInt(bitPattern: $0)) } ?? "nil"
        let fndDirect = kclassFndDirect.map { String(format: "0x%014llx", UInt(bitPattern: $0)) } ?? "nil"
        let bizDirect = kclassBizDirect.map { String(format: "0x%014llx", UInt(bitPattern: $0)) } ?? "nil"
        let addrMatch = kclassFnd != nil && kclassFnd == kclassBizDirect

        let status = """
        is-check(Kotlin): \(isCheck ? "✅ true" : "❌ false")\(castResult)
        kclass(RTLD_DEFAULT): \(fndAddr)
        kclass(foundationKit): \(fndDirect)
        kclass(businessKit):   \(bizDirect)
        addrs match: \(addrMatch ? "✅ YES" : "❌ NO / nil")
        """
        NSLog("[KMT-2364] isCheck=%@ kclassFnd=%@ kclassBiz=%@ match=%@",
              isCheck ? "true" : "false", fndDirect ?? "nil", bizDirect ?? "nil", addrMatch ? "YES" : "NO")
        kmt2364Status = status
        kmt2364Color = isCheck ? .green : (addrMatch ? .orange : .red)
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
        results.append("[IS CHECK] Business obj is SharedData?    \(valBusiness ? "✅ true" : "❌ false")")
        results.append("[IS CHECK] Foundation obj is SharedData?  \(valFoundation ? "✅ true" : "❌ false")")
        results.append("")

        // 3d: Process business-created object (should always work)
        let processOk = processor.forceProcessAny(data: fromBusiness)
        results.append("[CAST] Business->Business forceProcessAny: ✅ \(processOk)")

        // 3e: Process foundation-created object (KMT-2364 phase 2 fix: now works!)
        if valFoundation {
            let processFnd = processor.forceProcessAny(data: fromFoundation)
            results.append("[CAST] Foundation->Business forceProcessAny: ✅ \(processFnd)")
        } else {
            results.append("[CAST] Foundation->Business forceProcessAny: ❌ SKIPPED")
            results.append("  reason: Kotlin 'as SharedData' would throw ClassCastException")
        }
        results.append("")

        // 3f: Class name from each runtime's perspective
        let businessClassName = processor.getSharedDataClassName()
        results.append("[CLASS] Business runtime sees: \(businessClassName)")
        results.append("")

        // Summary
        let crossFrameworkFixed = valFoundation
        results.append("══════ SUMMARY ══════")
        results.append("Single K/N runtime (foundationKit): \(crossFrameworkFixed ? "✅ YES" : "❌ NO")")
        results.append("Cross-framework is/as works:        \(crossFrameworkFixed ? "✅ YES" : "❌ NO")")
        results.append("KMT-2364 phase 2 (externalKlibs):   \(crossFrameworkFixed ? "✅ FIXED" : "❌ NOT YET")")

        // Log to system log for simctl capture
        NSLog("[KMT-2364] ============================================")
        for line in results { NSLog("[KMT-2364] %@", line) }
        NSLog("[KMT-2364] ============================================")
    }

    func runPhase2Tests() {
        results = []
        results.append("══════ PHASE 2: CROSS-FRAMEWORK TYPE IDENTITY ══════")
        results.append("All objects created by foundationKit, passed as Any to businessKit")
        results.append("")

        let net = businessKit.NetworkProcessor()
        var passed = 0
        var total = 0

        // Create data class objects via foundationKit (producer)
        let req: Any = foundationKit.TypeTestModelsKt.createRequest(endpoint: "/api/users")
        let resp200: Any = foundationKit.TypeTestModelsKt.createResponse(code: 200, body: "ok", source: nil)

        // --- T1: is-check on plain data classes ---
        total += 1
        let isReq = net.isRequest(obj: req)
        let isResp = net.isResponse(obj: resp200)
        let t1ok = isReq && isResp
        results.append("[\(t1ok ? "✅" : "❌")] T1: is-check on data classes")
        results.append("  isRequest(RequestPayload)=\(isReq)")
        results.append("  isResponse(ResponseResult)=\(isResp)")
        if t1ok { passed += 1 }
        results.append("")

        // --- T2: Takes A returns B (Any→as RequestPayload→execute→ResponseResult) ---
        total += 1
        let resp = net.processAnyRequest(obj: req)
        let t2ok = resp.body == "OK from /api/users"
        results.append("[\(t2ok ? "✅" : "❌")] T2: Takes A returns B")
        results.append("  Any→as RequestPayload→execute→ResponseResult")
        results.append("  resp.body=\"\(resp.body)\"")
        if t2ok { passed += 1 }
        results.append("")

        // --- T3: Nested cross-framework reference ---
        total += 1
        let srcEndpoint = net.getSourceEndpoint(obj: resp)
        let t3ok = srcEndpoint == "/api/users"
        results.append("[\(t3ok ? "✅" : "❌")] T3: Nested reference round-trip")
        results.append("  resp.source.endpoint=\"\(srcEndpoint)\"")
        if t3ok { passed += 1 }
        results.append("")

        // --- T4: Double cast round-trip ---
        total += 1
        let step1 = net.processAnyRequest(obj: req)
        let step2 = net.isResponse(obj: step1)
        let step3 = net.getSourceEndpoint(obj: step1)
        let t4ok = step2 && step3 == "/api/users"
        results.append("[\(t4ok ? "✅" : "❌")] T4: Double cast round-trip")
        results.append("  req(fnd)→resp(biz)→isResponse=\(step2), source=\"\(step3)\"")
        if t4ok { passed += 1 }
        results.append("")

        // --- T5: Sealed class is-checks (type identity only, no field access) ---
        let loading: Any = foundationKit.TypeTestModelsKt.createLoadingState(progress: 0.5)
        let success: Any = foundationKit.TypeTestModelsKt.createSuccessState(result: resp200 as! foundationKit.ResponseResult)
        let err: Any = foundationKit.TypeTestModelsKt.createErrorState(message: "timeout", retryable: true)

        total += 1
        let sealedChecks: [(String, Bool)] = [
            ("isNetworkState(Loading)",  net.isNetworkState(obj: loading)),
            ("isLoadingState(Loading)",  net.isLoadingState(obj: loading)),
            ("isSuccessState(Success)",  net.isSuccessState(obj: success)),
            ("isErrorState(Error)",      net.isErrorState(obj: err)),
            ("isNetworkState(Success)",  net.isNetworkState(obj: success)),
            ("isNetworkState(Error)",    net.isNetworkState(obj: err)),
        ]
        let allSealed = sealedChecks.allSatisfy { $0.1 }
        results.append("[\(allSealed ? "✅" : "❌")] T5: Sealed class is-checks (6 checks)")
        for (name, ok) in sealedChecks {
            results.append("  \(ok ? "✅" : "❌") \(name)")
        }
        if allSealed { passed += 1 }
        results.append("")

        // --- T6: Collection type filtering ---
        total += 1
        let mixed: [Any] = [loading, success, err, success]
        let successCount = net.countSuccessInList(items: mixed)
        let t6ok = successCount == 2
        results.append("[\(t6ok ? "✅" : "❌")] T6: Collection filtering (is NetworkState.Success)")
        results.append("  [Loading, Success, Error, Success] → count=\(successCount) (expect 2)")
        if t6ok { passed += 1 }
        results.append("")

        // --- T7: Sealed class when-matching with field access ---
        // This tests that businessKit can access fields of foundation-created sealed subtypes
        // NOTE: This may crash if vtable dispatch is broken for sealed class field access
        total += 1
        results.append("[⏳] T7: Sealed class when + field access (may crash if vtable broken)")
        NSLog("[KMT-2364-P2] About to test describeStateAny (sealed when + field access)...")

        // Flush results so far before potentially crashing
        NSLog("[KMT-2364-P2] ============================================")
        for line in results { NSLog("[KMT-2364-P2] %@", line) }
        NSLog("[KMT-2364-P2] Pre-T7 RESULT: %d/%d passed so far", passed, total - 1)
        NSLog("[KMT-2364-P2] ============================================")

        let d1 = net.describeStateAny(obj: loading)
        let d2 = net.describeStateAny(obj: success)
        let d3 = net.describeStateAny(obj: err)
        let t7ok = d1.contains("loading") && d2.contains("success") && d3.contains("error")
        // Remove the placeholder
        results.removeLast()
        results.append("[\(t7ok ? "✅" : "❌")] T7: Sealed class when + field access")
        results.append("  Loading → \(d1)")
        results.append("  Success → \(d2)")
        results.append("  Error   → \(d3)")
        if t7ok { passed += 1 }
        results.append("")

        // --- Summary ---
        results.append("══════ RESULT: \(passed)/\(total) PASSED ══════")
        let allPassed = passed == total
        results.append(allPassed
            ? "✅ ALL CROSS-FRAMEWORK TYPE IDENTITY TESTS PASS"
            : "❌ SOME TESTS FAILED")

        NSLog("[KMT-2364-P2] ============================================")
        for line in results { NSLog("[KMT-2364-P2] %@", line) }
        NSLog("[KMT-2364-P2] ============================================")
        NSLog("[KMT-2364-P2] RESULT: %d/%d passed", passed, total)
    }
}

#Preview {
    ContentView()
}
