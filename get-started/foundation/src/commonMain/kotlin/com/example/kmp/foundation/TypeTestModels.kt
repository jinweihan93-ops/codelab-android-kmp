package com.example.kmp.foundation

data class RequestPayload(
    val endpoint: String,
    val params: Map<String, String> = emptyMap()
)

data class ResponseResult(
    val code: Int,
    val body: String,
    val source: RequestPayload? = null
)

sealed interface NetworkState {
    data class Loading(val progress: Float) : NetworkState
    data class Success(val data: ResponseResult) : NetworkState
    data class Error(val message: String, val retryable: Boolean) : NetworkState
}

fun createRequest(endpoint: String): RequestPayload =
    RequestPayload(endpoint)

fun createResponse(code: Int, body: String, source: RequestPayload?): ResponseResult =
    ResponseResult(code, body, source)

fun createLoadingState(progress: Float): NetworkState =
    NetworkState.Loading(progress)

fun createSuccessState(result: ResponseResult): NetworkState =
    NetworkState.Success(result)

fun createErrorState(message: String, retryable: Boolean): NetworkState =
    NetworkState.Error(message, retryable)
