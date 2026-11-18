/* Reversible USER_INITIATED overrides for exactly ten identified research worker pthreads.
 * No Java/JVMTI API, game state, VM flag, model, clock, or bytecode is inspected or modified.
 * Options: begin:<expected-pid>:<new-absolute-receipt> or end:<expected-pid>:<new-receipt>.
 * Thread names must be exactly pool-1-thread-1 through pool-1-thread-10 before any override.
 */
#include <jni.h>
#include <pthread.h>
#include <pthread/qos.h>
#include <mach/mach.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define WORKERS 10
typedef struct {
    pthread_t thread;
    mach_port_t port;
    qos_class_t requested_qos;
    int requested_relative_priority;
    pthread_override_t override;
} worker;

static pthread_mutex_t control_lock = PTHREAD_MUTEX_INITIALIZER;
static worker selected[WORKERS];

static int active_count(void) {
    int count = 0;
    for (int index = 0; index < WORKERS; index++) {
        count += selected[index].override != NULL;
    }
    return count;
}

static int release_overrides(void) {
    int errors = 0;
    for (int index = 0; index < WORKERS; index++) {
        if (selected[index].override != NULL) {
            int result = pthread_override_qos_class_end_np(selected[index].override);
            if (result == 0) {
                selected[index].override = NULL;
            } else {
                errors++;
            }
        }
    }
    return errors;
}

static int identify_workers(void) {
    thread_act_array_t threads = NULL;
    mach_msg_type_number_t count = 0;
    if (task_threads(mach_task_self(), &threads, &count) != KERN_SUCCESS) {
        return -1;
    }
    int found = 0;
    for (mach_msg_type_number_t thread_index = 0; thread_index < count; thread_index++) {
        pthread_t thread = pthread_from_mach_thread_np(threads[thread_index]);
        char name[64] = {0};
        if (thread != NULL && pthread_getname_np(thread, name, sizeof(name)) == 0) {
            for (int index = 0; index < WORKERS; index++) {
                char expected[32];
                snprintf(expected, sizeof(expected), "pool-1-thread-%d", index + 1);
                if (strcmp(name, expected) == 0) {
                    if (selected[index].thread != NULL) {
                        found = -100;
                    } else {
                        selected[index].thread = thread;
                        selected[index].port = threads[thread_index];
                        pthread_get_qos_class_np(thread, &selected[index].requested_qos,
                            &selected[index].requested_relative_priority);
                        found++;
                    }
                }
            }
        }
        mach_port_deallocate(mach_task_self(), threads[thread_index]);
    }
    vm_deallocate(mach_task_self(), (vm_address_t)threads, count * sizeof(thread_t));
    return found;
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    (void)vm;
    (void)reserved;
    if (options == NULL) {
        return JNI_ERR;
    }
    int begin = strncmp(options, "begin:", 6) == 0;
    int end = strncmp(options, "end:", 4) == 0;
    if (!begin && !end) {
        return JNI_ERR;
    }
    char *after_pid = NULL;
    errno = 0;
    long expected_pid = strtol(options + (begin ? 6 : 4), &after_pid, 10);
    if (errno != 0 || expected_pid != getpid() || after_pid == NULL ||
        after_pid[0] != ':' || after_pid[1] != '/' || strlen(after_pid + 1) >= PATH_MAX) {
        return JNI_ERR;
    }
    int descriptor = open(after_pid + 1, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    if (descriptor < 0) {
        return JNI_ERR;
    }
    FILE *receipt = fdopen(descriptor, "w");
    if (receipt == NULL) {
        close(descriptor);
        return JNI_ERR;
    }
    pthread_mutex_lock(&control_lock);
    int before = active_count();
    int identified = 0;
    int errors = 0;
    if (begin && before == 0) {
        memset(selected, 0, sizeof(selected));
        identified = identify_workers();
        if (identified == WORKERS) {
            for (int index = 0; index < WORKERS; index++) {
                selected[index].override = pthread_override_qos_class_start_np(
                    selected[index].thread, QOS_CLASS_USER_INITIATED, 0);
                errors += selected[index].override == NULL;
            }
        } else {
            errors++;
        }
        if (errors != 0) {
            errors += release_overrides();
        }
    } else if (end) {
        errors = release_overrides();
    } else {
        errors++;
    }
    int after = active_count();
    fprintf(receipt, "{\"kind\":\"mac-worker-qos-operation-1\",\"pid\":%d,"
        "\"operation\":\"%s\",\"identifiedWorkers\":%d,\"activeBefore\":%d,"
        "\"activeAfter\":%d,\"errors\":%d,\"requestedOverrideClass\":%u,\"workers\":[",
        getpid(), begin ? "begin" : "end", identified, before, after, errors,
        (unsigned)QOS_CLASS_USER_INITIATED);
    for (int index = 0; index < WORKERS; index++) {
        fprintf(receipt, "%s{\"name\":\"pool-1-thread-%d\",\"machPort\":%u,"
            "\"originalRequestedQos\":%u,\"originalRelativePriority\":%d,\"overrideActive\":%s}",
            index == 0 ? "" : ",", index + 1, selected[index].port,
            (unsigned)selected[index].requested_qos, selected[index].requested_relative_priority,
            selected[index].override != NULL ? "true" : "false");
    }
    fprintf(receipt, "],\"javaStateModified\":false,\"scientificInputsModified\":false}\n");
    fflush(receipt);
    fsync(descriptor);
    fclose(receipt);
    pthread_mutex_unlock(&control_lock);
    return errors == 0 && after == (begin ? WORKERS : 0) ? JNI_OK : JNI_ERR;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    (void)vm;
    pthread_mutex_lock(&control_lock);
    release_overrides();
    pthread_mutex_unlock(&control_lock);
}
