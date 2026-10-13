/*
 * JNI bridge between UxPlay's protocol library and the Kotlin side.
 *
 * The library does the networking (RTSP, pairing, FairPlay, RTP, mDNS) on its own threads and
 * hands decrypted H.264 and audio frames to the callbacks below. Those are forwarded to
 * com.nithin.nightcast.NativeReceiver, which decodes them with MediaCodec / AudioTrack.
 * ALAC (what iOS sends for plain music) has no guaranteed Android decoder, so it is decoded
 * here and forwarded as PCM.
 *
 * This file takes the place of UxPlay's uxplay.cpp, and like it is GPL-3.0.
 */

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "alac.h"

#define TAG "NightcastNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Seconds without the client's two-second heartbeat before the connection is declared dead. */
#define MISSED_FEEDBACK_LIMIT 15
#define ALAC_MAX_FRAME_BYTES (4096 * 4)

static JavaVM *g_vm;
static pthread_key_t g_env_key;
static jobject g_listener;

static jmethodID m_connections, m_client, m_mirror, m_video, m_video_reset, m_audio_format,
        m_audio, m_audio_flush, m_volume, m_metadata, m_cover_art, m_progress, m_pin,
        m_remote_control, m_cast_play, m_cast_seek, m_cast_rate, m_cast_stop, m_photo,
        m_slideshow, m_slide_photo;

static raop_t *g_raop;
static dnssd_t *g_dnssd;

/* The library must not be torn down from inside its own callbacks, so callbacks only raise
 * flags and this thread acts on them (the job of the GLib main loop in uxplay.cpp). */
static pthread_t g_control_thread;
static volatile bool g_running;
static volatile bool g_reset_httpd;
static volatile int g_open_connections;
static volatile int g_missed_feedback;

static double g_client_volume = 0.0;

/* Public keys of the phones that have entered the on-screen PIN, one base64 key per line start
 * (the file format of UxPlay's own register: "key,deviceID,name"). */
#define MAX_REGISTERED 64
#define PK64_LEN 44
static char g_registered[MAX_REGISTERED][PK64_LEN + 1];
static int g_registered_count;
static char g_register_file[512];
static pthread_mutex_t g_register_lock = PTHREAD_MUTEX_INITIALIZER;

/* The casting player lives on the Kotlin side; it pushes its state here so the library's
 * playback-info requests can be answered without calling up into Java. */
static playback_info_t g_playback;
static bool g_playback_finished;
static pthread_mutex_t g_playback_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_language[64] = "en";
static alac_file *g_alac;
static pthread_mutex_t g_alac_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- JNI plumbing ----------------------------------------------------------------------------- */

static void detach_thread(void *unused) {
    (*g_vm)->DetachCurrentThread(g_vm);
}

/* Library threads are plain pthreads: attach on first use, detach when the thread exits. */
static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
        return NULL;
    }
    pthread_setspecific(g_env_key, env);
    return env;
}

static void clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static jbyteArray to_byte_array(JNIEnv *env, const void *data, int len) {
    jbyteArray array = (*env)->NewByteArray(env, len);
    if (array) {
        (*env)->SetByteArrayRegion(env, array, 0, len, (const jbyte *) data);
    }
    return array;
}

/* ---- Library callbacks ------------------------------------------------------------------------ */

static void report_connections(void) {
    JNIEnv *env = get_env();
    if (env && g_listener) {
        (*env)->CallVoidMethod(env, g_listener, m_connections, (jint) g_open_connections);
        clear_exception(env);
    }
}

static void conn_init(void *cls) {
    g_open_connections++;
    report_connections();
}

static void conn_destroy(void *cls) {
    g_open_connections--;
    report_connections();
}

static void conn_feedback(void *cls) {
    g_missed_feedback = 0;
}

static void conn_reset(void *cls, int reason) {
    LOGI("connection reset requested, reason %d", reason);
    g_reset_httpd = true;
}

static void report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    *admit = true;
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    jstring jname = (*env)->NewStringUTF(env, name ? name : "");
    jstring jmodel = (*env)->NewStringUTF(env, model ? model : "");
    (*env)->CallVoidMethod(env, g_listener, m_client, jname, jmodel);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, jmodel);
}

static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    /* An Annex-B buffer starts with a 00 00 00 01 start code; the library sets the first byte
     * to 1 when decryption produced garbage. */
    if (data->data_len < 5 || data->data[0] != 0) return;
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    jbyteArray array = to_byte_array(env, data->data, data->data_len);
    if (!array) return;
    (*env)->CallVoidMethod(env, g_listener, m_video, array, (jint) data->data_len,
                           (jlong) data->ntp_time_remote, (jboolean) data->is_h265);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, array);
}

static void mirror_video_running(void *cls, bool is_running) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, m_mirror, (jboolean) is_running);
    clear_exception(env);
}

static void video_reset(void *cls, reset_type_t type) {
    /* What uxplay.cpp does for these resets: a cast that ends, or is replaced by another
     * client, leaves playlists and connections behind that have to be dropped. */
    if (g_raop && (type == RESET_TYPE_NOHOLD || type == RESET_TYPE_HLS_SHUTDOWN)) {
        raop_destroy_airplay_video(g_raop, -1);
    }
    if (g_raop && type == RESET_TYPE_HLS_SHUTDOWN) {
        raop_remove_hls_connections(g_raop);
    }
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, m_video_reset, (jint) type);
    clear_exception(env);
}

/* The decoder shows whatever arrives, so there is nothing to pause, resume or flush. */
static void video_pause(void *cls) {}
static void video_resume(void *cls) {}
static void video_flush(void *cls) {}

static int video_set_codec(void *cls, video_codec_t codec) {
    /* H.265 is never offered (features bit 42 is off), so anything else is a protocol error. */
    return codec == VIDEO_CODEC_H264 ? 0 : -1;
}

static void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                             bool *isMedia, uint64_t *audioFormat) {
    LOGI("audio format: ct=%d spf=%d usingScreen=%d isMedia=%d", *ct, *spf, *usingScreen, *isMedia);
    if (*ct == 2) {
        pthread_mutex_lock(&g_alac_lock);
        if (g_alac) alac_free(g_alac);
        /* The fixed ALAC parameters AirPlay uses: 44.1 kHz, 16 bit, stereo. */
        g_alac = alac_create(16, 2);
        g_alac->setinfo_max_samples_per_frame = *spf;
        g_alac->setinfo_7a = 0;
        g_alac->setinfo_sample_size = 16;
        g_alac->setinfo_rice_historymult = 40;
        g_alac->setinfo_rice_initialhistory = 10;
        g_alac->setinfo_rice_kmodifier = 14;
        g_alac->setinfo_7f = 2;
        g_alac->setinfo_80 = 255;
        g_alac->setinfo_82 = 0;
        g_alac->setinfo_86 = 0;
        g_alac->setinfo_8a_rate = 44100;
        alac_allocate_buffers(g_alac);
        pthread_mutex_unlock(&g_alac_lock);
    }
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, m_audio_format, (jint) *ct, (jint) *spf);
    clear_exception(env);
}

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    if (data->data_len <= 0) return;
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;

    jbyteArray array = NULL;
    int len = 0;
    jboolean is_pcm = JNI_FALSE;
    if (data->ct == 2) {
        /* A valid ALAC frame starts with 0x20; anything else is a failed decryption. */
        unsigned char pcm[ALAC_MAX_FRAME_BYTES];
        pthread_mutex_lock(&g_alac_lock);
        if (g_alac && data->data[0] == 0x20) {
            len = sizeof(pcm);
            alac_decode_frame(g_alac, data->data, pcm, &len);
        }
        pthread_mutex_unlock(&g_alac_lock);
        if (len <= 0) return;
        array = to_byte_array(env, pcm, len);
        is_pcm = JNI_TRUE;
    } else {
        len = data->data_len;
        array = to_byte_array(env, data->data, len);
    }
    if (!array) return;
    (*env)->CallVoidMethod(env, g_listener, m_audio, array, (jint) len, is_pcm,
                           (jlong) data->ntp_time_remote);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, array);
}

static void audio_flush(void *cls) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, m_audio_flush);
    clear_exception(env);
}

/* The volume the client's slider starts at, in AirPlay dB (0 = full). */
static double audio_set_client_volume(void *cls) {
    return g_client_volume;
}

static void audio_set_volume(void *cls, float volume) {
    g_client_volume = volume;
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, m_volume, (jfloat) volume);
    clear_exception(env);
}

static void send_bytes(jmethodID method, const void *buffer, int buflen) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    jbyteArray array = (buffer && buflen > 0) ? to_byte_array(env, buffer, buflen) : NULL;
    (*env)->CallVoidMethod(env, g_listener, method, array);
    clear_exception(env);
    if (array) (*env)->DeleteLocalRef(env, array);
}

static void audio_set_metadata(void *cls, const void *buffer, int buflen) {
    send_bytes(m_metadata, buffer, buflen);
}

static void audio_set_coverart(void *cls, const void *buffer, int buflen) {
    send_bytes(m_cover_art, buffer, buflen);
}

static void audio_stop_coverart_rendering(void *cls) {
    send_bytes(m_cover_art, NULL, 0);
}

static void audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, m_progress, (jlong) *start, (jlong) *curr, (jlong) *end);
    clear_exception(env);
}

/* The phone's DACP identity: with it the head unit can send play/pause/skip back. */
static void audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote_header) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    jstring jid = (*env)->NewStringUTF(env, dacp_id);
    jstring jremote = (*env)->NewStringUTF(env, active_remote_header);
    (*env)->CallVoidMethod(env, g_listener, m_remote_control, jid, jremote);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, jid);
    (*env)->DeleteLocalRef(env, jremote);
}

/* ---- Access control: on-screen PIN, asked once per phone -------------------------------------- */

/* No shared password; when a PIN is required the library runs its own pairing instead. */
static const char *passwd(void *cls, int *len) {
    *len = 0;
    return NULL;
}

static void display_pin(void *cls, char *pin) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    jstring jpin = (*env)->NewStringUTF(env, pin);
    (*env)->CallVoidMethod(env, g_listener, m_pin, jpin);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, jpin);
}

static void load_register(void) {
    pthread_mutex_lock(&g_register_lock);
    g_registered_count = 0;
    FILE *fp = fopen(g_register_file, "r");
    if (fp) {
        char line[512];
        while (g_registered_count < MAX_REGISTERED && fgets(line, sizeof(line), fp)) {
            if (strlen(line) < PK64_LEN) continue;
            memcpy(g_registered[g_registered_count], line, PK64_LEN);
            g_registered[g_registered_count][PK64_LEN] = '\0';
            g_registered_count++;
        }
        fclose(fp);
    }
    pthread_mutex_unlock(&g_register_lock);
}

static void register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    if (strlen(pk_str) < PK64_LEN) return;
    pthread_mutex_lock(&g_register_lock);
    if (g_registered_count < MAX_REGISTERED) {
        memcpy(g_registered[g_registered_count], pk_str, PK64_LEN);
        g_registered[g_registered_count][PK64_LEN] = '\0';
        g_registered_count++;
        FILE *fp = fopen(g_register_file, "a");
        if (fp) {
            fprintf(fp, "%.44s,%s,%s\n", pk_str, device_id, name);
            fclose(fp);
        }
    }
    pthread_mutex_unlock(&g_register_lock);
    LOGI("paired with %s", name);
}

static bool check_register(void *cls, const char *pk_str) {
    bool found = false;
    pthread_mutex_lock(&g_register_lock);
    for (int i = 0; i < g_registered_count && !found; i++) {
        found = strncmp(g_registered[i], pk_str, PK64_LEN) == 0;
    }
    pthread_mutex_unlock(&g_register_lock);
    return found;
}

/* ---- Casting ("AirPlay video": an app hands over a stream URL instead of mirroring) ----------- */

static void reset_playback(void) {
    pthread_mutex_lock(&g_playback_lock);
    memset(&g_playback, 0, sizeof(g_playback));
    g_playback.position = -1.0;
    g_playback_finished = false;
    pthread_mutex_unlock(&g_playback_lock);
}

static void call_float(jmethodID method, float value) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, method, (jfloat) value);
    clear_exception(env);
}

static void on_video_play(void *cls, const char *location, const float start_position) {
    reset_playback();
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    jstring jlocation = (*env)->NewStringUTF(env, location);
    (*env)->CallVoidMethod(env, g_listener, m_cast_play, jlocation, (jfloat) start_position);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, jlocation);
}

static void on_video_scrub(void *cls, const float position) {
    call_float(m_cast_seek, position);
}

static void on_video_rate(void *cls, const float rate) {
    call_float(m_cast_rate, rate);
}

static void on_video_stop(void *cls) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, m_cast_stop);
    clear_exception(env);
}

/* A duration and position of -1 tell the library the video is over, and it winds the cast down;
 * a position of -1 alone means "nothing to report yet". */
static void on_video_acquire_playback_info(void *cls, playback_info_t *playback_info) {
    pthread_mutex_lock(&g_playback_lock);
    playback_info->duration = g_playback.duration;
    playback_info->position = g_playback.position;
    playback_info->seek_start = g_playback.seek_start;
    playback_info->seek_duration = g_playback.seek_duration;
    playback_info->rate = g_playback.rate;
    playback_info->playback_buffer_empty = g_playback.playback_buffer_empty;
    playback_info->playback_buffer_full = g_playback.playback_buffer_full;
    playback_info->ready_to_play = true;
    playback_info->playback_likely_to_keep_up = true;
    if (g_playback_finished) {
        playback_info->position = -1.0;
        playback_info->duration = -1.0;
    }
    pthread_mutex_unlock(&g_playback_lock);
}

static float on_video_playlist_remove(void *cls) {
    call_float(m_cast_rate, 0.0f);
    pthread_mutex_lock(&g_playback_lock);
    float position = (float) g_playback.position;
    pthread_mutex_unlock(&g_playback_lock);
    return position < 0.0f ? 0.0f : position;
}

static void log_callback(void *cls, int level, const char *msg) {
    int priority = level <= LOGGER_ERR ? ANDROID_LOG_ERROR
                 : level == LOGGER_WARNING ? ANDROID_LOG_WARN
                 : level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_write(priority, "UxPlay", msg);
}

/* ---- Photos (the AirPlay button on a picture; see http_handler_photo) -------------------------- */

static bool on_photo(void *cls, const char *asset_key, const char *asset_action, const void *data,
                     int datalen) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return true;
    jstring jkey = asset_key ? (*env)->NewStringUTF(env, asset_key) : NULL;
    jstring jaction = asset_action ? (*env)->NewStringUTF(env, asset_action) : NULL;
    jbyteArray array = (data && datalen > 0) ? to_byte_array(env, data, datalen) : NULL;
    jboolean ok = (*env)->CallBooleanMethod(env, g_listener, m_photo, jkey, jaction, array);
    if ((*env)->ExceptionCheck(env)) {
        clear_exception(env);
        ok = JNI_TRUE;
    }
    if (jkey) (*env)->DeleteLocalRef(env, jkey);
    if (jaction) (*env)->DeleteLocalRef(env, jaction);
    if (array) (*env)->DeleteLocalRef(env, array);
    return ok;
}

/* ---- Slideshows (the receiver pulls the pictures; see http_handler_slideshow) ------------------ */

static void on_slideshow(void *cls, bool playing, int slide_duration) {
    JNIEnv *env = get_env();
    if (!env || !g_listener) return;
    (*env)->CallVoidMethod(env, g_listener, m_slideshow, (jboolean) playing, (jint) slide_duration);
    clear_exception(env);
}

static void on_slideshow_photo(void *cls, int asset_id, const void *data, int datalen) {
    send_bytes(m_slide_photo, data, datalen);
}

JNIEXPORT void JNICALL
Java_com_nithin_nightcast_NativeReceiver_nativeRequestSlide(JNIEnv *env, jobject thiz, jint asset_id) {
    if (g_raop) raop_slideshow_request(g_raop, asset_id);
}

/* ---- Control thread --------------------------------------------------------------------------- */

static void *control_loop(void *arg) {
    int ticks = 0;
    while (g_running) {
        usleep(100 * 1000);
        if (++ticks >= 10) {
            ticks = 0;
            if (g_open_connections > 0) {
                if (++g_missed_feedback > MISSED_FEEDBACK_LIMIT) {
                    LOGE("no heartbeat from the client for %d s, dropping it", MISSED_FEEDBACK_LIMIT);
                    g_reset_httpd = true;
                }
            } else {
                g_missed_feedback = 0;
            }
        }
        if (g_reset_httpd && g_running) {
            g_reset_httpd = false;
            g_missed_feedback = 0;
            /* Restarting the HTTP server closes every connection and frees their streams. */
            unsigned short port = raop_get_port(g_raop);
            raop_stop_httpd(g_raop);
            video_reset(NULL, RESET_TYPE_NOHOLD);
            raop_start_httpd(g_raop, &port);
            raop_set_port(g_raop, port);
        }
    }
    return NULL;
}

/* ---- Start / stop ----------------------------------------------------------------------------- */

static void destroy_server(void) {
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = NULL;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    g_open_connections = 0;
}

/* Feature bits advertised over mDNS, as set by uxplay.cpp for mirroring, audio and casting
 * (bits 0 and 4: AirPlay video and HLS), without H.265 or legacy pairing. */
static void set_features(dnssd_t *dnssd) {
    static const int on[] = { 0, 1, 2, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                              21, 22, 25, 28, 30 };
    for (int bit = 0; bit < 62; bit++) {
        dnssd_set_airplay_features(dnssd, bit, 0);
    }
    for (size_t i = 0; i < sizeof(on) / sizeof(on[0]); i++) {
        dnssd_set_airplay_features(dnssd, on[i], 1);
    }
}

JNIEXPORT jint JNICALL
Java_com_nithin_nightcast_NativeReceiver_nativeStart(JNIEnv *env, jobject thiz, jstring jname,
                                                  jbyteArray jhw_addr, jstring jkey_file,
                                                  jstring jregister_file, jstring jlanguage,
                                                  jint width, jint height, jint fps,
                                                  jboolean require_pin, jboolean debug) {
    if (g_raop) return -1;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *key_file = (*env)->GetStringUTFChars(env, jkey_file, NULL);
    const char *register_file = (*env)->GetStringUTFChars(env, jregister_file, NULL);
    const char *language = (*env)->GetStringUTFChars(env, jlanguage, NULL);
    snprintf(g_register_file, sizeof(g_register_file), "%s", register_file);
    if (strlen(language)) snprintf(g_language, sizeof(g_language), "%s", language);
    (*env)->ReleaseStringUTFChars(env, jregister_file, register_file);
    (*env)->ReleaseStringUTFChars(env, jlanguage, language);
    load_register();
    reset_playback();
    char hw_addr[6];
    (*env)->GetByteArrayRegion(env, jhw_addr, 0, 6, (jbyte *) hw_addr);
    char device_id[18];
    snprintf(device_id, sizeof(device_id), "%02X:%02X:%02X:%02X:%02X:%02X",
             (unsigned char) hw_addr[0], (unsigned char) hw_addr[1], (unsigned char) hw_addr[2],
             (unsigned char) hw_addr[3], (unsigned char) hw_addr[4], (unsigned char) hw_addr[5]);

    int result = -1;
    int dnssd_error = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), hw_addr, sizeof(hw_addr), require_pin ? 1 : 0,
                         &dnssd_error);
    if (!g_dnssd || dnssd_error) {
        LOGE("dnssd_init failed: %d", dnssd_error);
        goto done;
    }
    set_features(g_dnssd);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = conn_init;
    cbs.conn_destroy = conn_destroy;
    cbs.conn_reset = conn_reset;
    cbs.conn_feedback = conn_feedback;
    cbs.report_client_request = report_client_request;
    cbs.audio_process = audio_process;
    cbs.video_process = video_process;
    cbs.audio_flush = audio_flush;
    cbs.video_flush = video_flush;
    cbs.video_pause = video_pause;
    cbs.video_resume = video_resume;
    cbs.video_reset = video_reset;
    cbs.video_set_codec = video_set_codec;
    cbs.mirror_video_running = mirror_video_running;
    cbs.audio_set_client_volume = audio_set_client_volume;
    cbs.audio_set_volume = audio_set_volume;
    cbs.audio_get_format = audio_get_format;
    cbs.audio_set_metadata = audio_set_metadata;
    cbs.audio_set_coverart = audio_set_coverart;
    cbs.audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    cbs.audio_set_progress = audio_set_progress;
    cbs.audio_remote_control_id = audio_remote_control_id;
    cbs.passwd = passwd;
    cbs.display_pin = display_pin;
    cbs.register_client = register_client;
    cbs.check_register = check_register;
    cbs.on_video_play = on_video_play;
    cbs.on_video_scrub = on_video_scrub;
    cbs.on_video_rate = on_video_rate;
    cbs.on_video_stop = on_video_stop;
    cbs.on_video_acquire_playback_info = on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = on_video_playlist_remove;
    cbs.on_photo = on_photo;
    cbs.on_slideshow = on_slideshow;
    cbs.on_slideshow_photo = on_slideshow_photo;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init failed");
        goto done;
    }
    raop_set_log_callback(g_raop, log_callback, NULL);
    raop_set_log_level(g_raop, debug ? LOGGER_DEBUG : LOGGER_INFO);
    /* nohold = 1: a second phone that connects takes over instead of being refused. */
    if (raop_init2(g_raop, 1, device_id, key_file)) {
        LOGE("raop_init2 failed");
        free(g_raop);
        g_raop = NULL;
        goto done;
    }

    /* The size the client is told to render for; it scales its screen to fit inside it. */
    raop_set_plist(g_raop, "width", width);
    raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "refreshRate", 60);
    raop_set_plist(g_raop, "maxFPS", fps);
    /* A PIN of 0 means a fresh random one is shown for each pairing attempt. */
    if (require_pin) raop_set_plist(g_raop, "pin", 0);
    raop_set_plist(g_raop, "hls", 1);
    /* The library keeps these pointers, hence the static buffer. */
    raop_set_lang(g_raop, NULL, NULL, g_language);

    unsigned short tcp[2] = { 0, 0 };
    unsigned short udp[3] = { 0, 0, 0 };
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) {
        LOGE("raop_start_httpd failed");
        goto done;
    }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);

    int err = dnssd_register_raop(g_dnssd, port);
    if (!err) err = dnssd_register_airplay(g_dnssd, port);
    if (err) {
        LOGE("mDNS registration failed: %d", err);
        goto done;
    }

    g_reset_httpd = false;
    g_missed_feedback = 0;
    g_running = true;
    pthread_create(&g_control_thread, NULL, control_loop, NULL);
    LOGI("\"%s\" listening on port %d as %s", name, port, device_id);
    result = port;

done:
    if (result < 0) destroy_server();
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jkey_file, key_file);
    return result;
}

JNIEXPORT void JNICALL
Java_com_nithin_nightcast_NativeReceiver_nativeStop(JNIEnv *env, jobject thiz) {
    if (!g_raop) return;
    g_running = false;
    pthread_join(g_control_thread, NULL);
    destroy_server();
    pthread_mutex_lock(&g_alac_lock);
    if (g_alac) {
        alac_free(g_alac);
        g_alac = NULL;
    }
    pthread_mutex_unlock(&g_alac_lock);
    LOGI("stopped");
}

JNIEXPORT void JNICALL
Java_com_nithin_nightcast_NativeReceiver_nativeSetPlaybackInfo(JNIEnv *env, jobject thiz,
                                                            jdouble duration, jdouble position,
                                                            jfloat rate, jboolean buffer_empty,
                                                            jboolean buffer_full,
                                                            jboolean finished) {
    pthread_mutex_lock(&g_playback_lock);
    g_playback.duration = duration;
    g_playback.position = position;
    g_playback.seek_start = 0.0;
    g_playback.seek_duration = duration;
    g_playback.rate = rate;
    g_playback.playback_buffer_empty = buffer_empty;
    g_playback.playback_buffer_full = buffer_full;
    g_playback_finished = finished;
    pthread_mutex_unlock(&g_playback_lock);
}

JNIEXPORT void JNICALL
Java_com_nithin_nightcast_NativeReceiver_nativeInit(JNIEnv *env, jobject thiz) {
    if (g_listener) (*env)->DeleteGlobalRef(env, g_listener);
    g_listener = (*env)->NewGlobalRef(env, thiz);
    jclass c = (*env)->GetObjectClass(env, thiz);
    m_connections = (*env)->GetMethodID(env, c, "onConnections", "(I)V");
    m_client = (*env)->GetMethodID(env, c, "onClient", "(Ljava/lang/String;Ljava/lang/String;)V");
    m_mirror = (*env)->GetMethodID(env, c, "onMirror", "(Z)V");
    m_video = (*env)->GetMethodID(env, c, "onVideo", "([BIJZ)V");
    m_video_reset = (*env)->GetMethodID(env, c, "onVideoReset", "(I)V");
    m_audio_format = (*env)->GetMethodID(env, c, "onAudioFormat", "(II)V");
    m_audio = (*env)->GetMethodID(env, c, "onAudio", "([BIZJ)V");
    m_audio_flush = (*env)->GetMethodID(env, c, "onAudioFlush", "()V");
    m_volume = (*env)->GetMethodID(env, c, "onVolume", "(F)V");
    m_metadata = (*env)->GetMethodID(env, c, "onMetadata", "([B)V");
    m_cover_art = (*env)->GetMethodID(env, c, "onCoverArt", "([B)V");
    m_progress = (*env)->GetMethodID(env, c, "onProgress", "(JJJ)V");
    m_pin = (*env)->GetMethodID(env, c, "onPin", "(Ljava/lang/String;)V");
    m_remote_control = (*env)->GetMethodID(env, c, "onRemoteControl", "(Ljava/lang/String;Ljava/lang/String;)V");
    m_cast_play = (*env)->GetMethodID(env, c, "onCastPlay", "(Ljava/lang/String;F)V");
    m_cast_seek = (*env)->GetMethodID(env, c, "onCastSeek", "(F)V");
    m_cast_rate = (*env)->GetMethodID(env, c, "onCastRate", "(F)V");
    m_cast_stop = (*env)->GetMethodID(env, c, "onCastStop", "()V");
    m_photo = (*env)->GetMethodID(env, c, "onPhoto", "(Ljava/lang/String;Ljava/lang/String;[B)Z");
    m_slideshow = (*env)->GetMethodID(env, c, "onSlideshow", "(ZI)V");
    m_slide_photo = (*env)->GetMethodID(env, c, "onSlidePhoto", "([B)V");
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    pthread_key_create(&g_env_key, detach_thread);
    return JNI_VERSION_1_6;
}
