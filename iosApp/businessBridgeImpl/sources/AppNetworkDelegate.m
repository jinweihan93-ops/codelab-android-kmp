/**
 * AppNetworkDelegate.m
 */

#import "AppNetworkDelegate.h"

@implementation AppNetworkDelegate

- (KMPNetworkReachability)reachability {
    // In production: query SCNetworkReachability or NWPathMonitor.
    return KMPNetworkReachabilityWiFi;
}

- (void)requestURL:(NSString *)url
            method:(NSString *)method
              body:(nullable NSString *)body
        completion:(void (^)(NSString * _Nullable responseBody,
                             NSInteger statusCode,
                             NSString * _Nullable errorMessage))completion {
    // Stub: simulate a short async round-trip.
    //
    // MUST call completion on the MAIN queue (not a background GCD queue).
    // Root cause of the previous crash:
    //   When completion fired on a background thread, K/N ObjC wrapper objects
    //   were dealloc'd on that background thread.  K/N's ObjC dealloc path sent
    //   an unrecognised selector to __NSGenericDeallocHandler → SIGABRT.
    // K/N's main worker thread handles ObjC dealloc correctly; background GCD
    // threads do not (they are not K/N-managed threads).
    //
    // In production: perform a real URLSession request and dispatch the completion
    // handler back to the main queue before calling this block.
    NSString *urlCopy    = [url copy];
    NSString *methodCopy = [method copy];
    dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.15 * NSEC_PER_SEC)),
        dispatch_get_main_queue(),                          // ← main queue, not global
        ^{
            NSString *stub = [NSString stringWithFormat:
                @"{\"status\":\"ok\",\"url\":\"%@\",\"method\":\"%@\"}",
                urlCopy, methodCopy];
            completion(stub, 200, nil);
        }
    );
}

@end
