/*
 * KMT-2364: GC / memory management test helpers.
 *
 * Verifies that embedRuntime=false results in a single shared GC instance
 * between foundationKit and businessKit. Tests that objects created by
 * foundationKit are tracked by the same GC that businessKit uses.
 */
package com.example.kmp.foundation

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.native.ref.WeakReference

/** Trigger a synchronous GC collection. Called from both Swift and businessKit. */
@OptIn(NativeRuntimeApi::class)
fun gcCollect() = GC.collect()

/**
 * T8 — Strong reference survives GC.
 * Creates a RequestPayload, triggers GC, and verifies the object is still alive and readable.
 * With a correctly shared runtime, GC only collects unreachable objects; a live strongly-held
 * reference must survive. A duplicated or mis-configured runtime could corrupt live objects.
 */
@OptIn(NativeRuntimeApi::class)
fun testStrongRefSurvivesGC(): Boolean {
    val obj = RequestPayload("/gc-strong-test")
    GC.collect()
    return obj.endpoint == "/gc-strong-test"
}

/**
 * T9 — Weak reference cleared after strong ref released and GC runs.
 * Object survives while a strong reference is held; WeakReference.get() returns null
 * after the strong ref is released and GC runs.
 *
 * With two independent runtimes, foundationKit's WeakReference would be tracked by a
 * different GC than the one that might collect the object — this test would then fail
 * (get() would remain non-null even after GC, or the runtime would crash).
 */
@OptIn(NativeRuntimeApi::class, ExperimentalNativeApi::class)
fun testWeakRefClearedAfterGC(): Boolean {
    var strong: RequestPayload? = RequestPayload("/gc-weak-test")
    val weak = WeakReference(strong!!)
    GC.collect()
    if (weak.get() == null) return false  // collected too early — strong ref should protect it
    strong = null                          // release the strong reference
    GC.collect()
    return weak.get() == null             // must be collected now
}

/** Creates an object for the cross-framework GC survival test (T10/T11 in businessKit). */
fun createGCTestPayload(): RequestPayload = RequestPayload("/cross-fw-gc")
