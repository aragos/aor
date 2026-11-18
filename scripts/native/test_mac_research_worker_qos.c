/* Engineering-only blocked pthreads; no simulation, model, random stream, or CPU workload. */
#include "mac_research_worker_qos.c"
#include <stdint.h>

static pthread_mutex_t probe_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t probe_ready = PTHREAD_COND_INITIALIZER;
static int ready_count = 0;
static int stop_workers = 0;

static void *blocked_worker(void *argument) {
    char name[32];
    snprintf(name, sizeof(name), "pool-1-thread-%d", (int)(intptr_t)argument + 1);
    pthread_setname_np(name);
    int policy = 0;
    struct sched_param parameters;
    if (pthread_getschedparam(pthread_self(), &policy, &parameters) == 0) {
        /* Match a JVM's legacy-priority pthread, permanently opted out of requested QoS. */
        pthread_setschedparam(pthread_self(), policy, &parameters);
    }
    pthread_mutex_lock(&probe_lock);
    ready_count++;
    pthread_cond_broadcast(&probe_ready);
    while (!stop_workers) {
        pthread_cond_wait(&probe_ready, &probe_lock);
    }
    pthread_mutex_unlock(&probe_lock);
    return NULL;
}

static void wait_ready(int count) {
    pthread_mutex_lock(&probe_lock);
    while (ready_count < count) {
        pthread_cond_wait(&probe_ready, &probe_lock);
    }
    pthread_mutex_unlock(&probe_lock);
}

int main(int argc, char **argv) {
    if (argc != 2 || argv[1][0] != '/') {
        return 2;
    }
    pthread_t threads[WORKERS];
    for (int index = 0; index < WORKERS; index++) {
        if (pthread_create(&threads[index], NULL, blocked_worker, (void *)(intptr_t)index) != 0) {
            return 3;
        }
        if (index == 8) {
            wait_ready(9);
            char options[PATH_MAX + 64];
            snprintf(options, sizeof(options), "begin:%d:%s/worker-selftest-nine.json", getpid(), argv[1]);
            if (Agent_OnAttach(NULL, options, NULL) != JNI_ERR || active_count() != 0) {
                return 4;
            }
        }
    }
    wait_ready(WORKERS);
    char options[PATH_MAX + 64];
    snprintf(options, sizeof(options), "begin:%d:%s/worker-selftest-begin.json", getpid(), argv[1]);
    if (Agent_OnAttach(NULL, options, NULL) != JNI_OK || active_count() != WORKERS) {
        return 5;
    }
    snprintf(options, sizeof(options), "end:%d:%s/worker-selftest-end.json", getpid(), argv[1]);
    if (Agent_OnAttach(NULL, options, NULL) != JNI_OK || active_count() != 0) {
        return 6;
    }
    pthread_mutex_lock(&probe_lock);
    stop_workers = 1;
    pthread_cond_broadcast(&probe_ready);
    pthread_mutex_unlock(&probe_lock);
    for (int index = 0; index < WORKERS; index++) {
        pthread_join(threads[index], NULL);
    }
    puts("{\"selfTestPassed\":true,\"legacyWorkers\":10,\"nineWorkersRejected\":true,\"allOverridesReleased\":true}");
    return 0;
}
