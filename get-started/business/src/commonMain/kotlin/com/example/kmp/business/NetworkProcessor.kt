package com.example.kmp.business

import com.example.kmp.foundation.NetworkState
import com.example.kmp.foundation.RequestPayload
import com.example.kmp.foundation.ResponseResult

class NetworkProcessor {

    fun executeRequest(request: RequestPayload): ResponseResult =
        ResponseResult(200, "OK from ${request.endpoint}", source = request)

    // All test methods take Any — this is the real KMT-2364 scenario:
    // objects created by foundationKit, passed as Any, checked/cast in businessKit

    // Sealed class when-matching via Any (internal cast)
    fun describeStateAny(obj: Any): String {
        val state = obj as NetworkState
        return when (state) {
            is NetworkState.Loading -> "loading(${state.progress})"
            is NetworkState.Success -> "success(code=${state.data.code})"
            is NetworkState.Error -> "error(${state.message}, retry=${state.retryable})"
        }
    }

    // Collection filtering via Any list
    fun countSuccessInList(items: List<Any>): Int =
        items.count { it is NetworkState.Success }

    // Type identity checks
    fun isRequest(obj: Any): Boolean = obj is RequestPayload
    fun isResponse(obj: Any): Boolean = obj is ResponseResult
    fun isNetworkState(obj: Any): Boolean = obj is NetworkState
    fun isLoadingState(obj: Any): Boolean = obj is NetworkState.Loading
    fun isSuccessState(obj: Any): Boolean = obj is NetworkState.Success
    fun isErrorState(obj: Any): Boolean = obj is NetworkState.Error

    // Round-trip: Any → as RequestPayload → execute → ResponseResult
    fun processAnyRequest(obj: Any): ResponseResult {
        val req = obj as RequestPayload
        return executeRequest(req)
    }

    // Verify cross-framework reference survives: cast response.source back
    fun getSourceEndpoint(obj: Any): String {
        val resp = obj as ResponseResult
        return resp.source?.endpoint ?: "null"
    }
}
