#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jni.h>
#include <stdlib.h>

JNIEXPORT jint JNICALL Java_tvbox_fixture_NativeMath_readAsset(JNIEnv *env, jclass type, jobject manager, jstring name) {
    AAssetManager *assets = AAssetManager_fromJava(env, manager);
    const char *path = (*env)->GetStringUTFChars(env, name, 0);
    AAsset *asset = AAssetManager_open(assets, path, AASSET_MODE_STREAMING);
    (*env)->ReleaseStringUTFChars(env, name, path);
    if (!asset) return -1;
    char value[8] = {0};
    int count = -2;
    if (AAsset_getLength(asset) < sizeof(value) && AAsset_read(asset, 0, 0) == 0) {
        count = AAsset_read(asset, value, (size_t)-1);
        if (AAsset_read(asset, value, 1) != 0) count = -2;
    }
    AAsset_close(asset);
    return count > 0 ? (jint)strtol(value, 0, 10) : -2;
}
