/* גשר JNI דק ל-LAME: אתחול ב-320kbps CBR, קידוד PCM 16bit, סגירה. בלי לוגים. */
#include <jni.h>
#include <stdint.h>
#include "lame.h"

#define FN(name) Java_com_king400_songcutter_audio_Mp3Encoder_##name

JNIEXPORT jlong JNICALL FN(nativeInit)(JNIEnv *env, jclass cls, jint inRate, jint channels, jint kbps, jint quality) {
    lame_t l = lame_init();
    if (!l) return 0;
    lame_set_in_samplerate(l, inRate);
    lame_set_out_samplerate(l, 44100);
    lame_set_num_channels(l, channels);
    lame_set_mode(l, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(l, vbr_off);           /* קצב קבוע */
    lame_set_brate(l, kbps);            /* 320 */
    lame_set_quality(l, quality);       /* 2 = איכות גבוהה */
    lame_set_bWriteVbrTag(l, 0);
    if (lame_init_params(l) < 0) { lame_close(l); return 0; }
    return (jlong)(intptr_t)l;
}

JNIEXPORT jint JNICALL FN(nativeEncode)(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jint frames, jint channels, jbyteArray out) {
    lame_t l = (lame_t)(intptr_t)h;
    if (!l) return -100;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize outLen = (*env)->GetArrayLength(env, out);
    int n;
    if (channels == 1) n = lame_encode_buffer(l, in, in, frames, (unsigned char *)o, outLen);
    else n = lame_encode_buffer_interleaved(l, in, frames, (unsigned char *)o, outLen);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}

JNIEXPORT jint JNICALL FN(nativeFlush)(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    lame_t l = (lame_t)(intptr_t)h;
    if (!l) return -100;
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int n = lame_encode_flush(l, (unsigned char *)o, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return n;
}

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *env, jclass cls, jlong h) {
    lame_t l = (lame_t)(intptr_t)h;
    if (l) lame_close(l);
}
