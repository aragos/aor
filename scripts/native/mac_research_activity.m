/* Process-scoped scheduling hint for explicitly requested, finite offline research.
 * This agent never calls Java/JVMTI APIs, inspects game state, changes VM flags, or instruments code.
 * Attach with "begin"; attach the same absolute library path with "end" to release the activity.
 * Process exit also releases the token. The external research owner retains all deadline control.
 */
#import <Foundation/Foundation.h>
#include <jni.h>
#include <pthread.h>
#include <string.h>

static pthread_mutex_t activity_lock = PTHREAD_MUTEX_INITIALIZER;
static id<NSObject> activity = nil;

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    (void)vm;
    (void)reserved;
    if (options == NULL || (strcmp(options, "begin") != 0 && strcmp(options, "end") != 0)) {
        return JNI_ERR;
    }
    @autoreleasepool {
        pthread_mutex_lock(&activity_lock);
        if (strcmp(options, "begin") == 0 && activity == nil) {
            activity = [[NSProcessInfo processInfo]
                beginActivityWithOptions:NSActivityUserInitiatedAllowingIdleSystemSleep
                reason:@"Explicit finite automata research"];
        } else if (strcmp(options, "end") == 0 && activity != nil) {
            [[NSProcessInfo processInfo] endActivity:activity];
            activity = nil;
        }
        jint result = strcmp(options, "begin") == 0 && activity == nil ? JNI_ERR : JNI_OK;
        pthread_mutex_unlock(&activity_lock);
        return result;
    }
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    (void)Agent_OnAttach(vm, "end", NULL);
}

#ifdef AOR_ACTIVITY_SELF_TEST
#include <stdio.h>
int main(void) {
    if (Agent_OnAttach(NULL, NULL, NULL) != JNI_ERR ||
        Agent_OnAttach(NULL, "unknown", NULL) != JNI_ERR ||
        Agent_OnAttach(NULL, "begin", NULL) != JNI_OK ||
        Agent_OnAttach(NULL, "begin", NULL) != JNI_OK ||
        Agent_OnAttach(NULL, "end", NULL) != JNI_OK ||
        Agent_OnAttach(NULL, "end", NULL) != JNI_OK) {
        return 1;
    }
    @autoreleasepool {
        NSProcessInfo *info = [NSProcessInfo processInfo];
        printf("{\"selfTestPassed\":true,\"thermalState\":%ld,\"lowPowerMode\":%s}\n",
            (long)info.thermalState, info.lowPowerModeEnabled ? "true" : "false");
    }
    return 0;
}
#endif
