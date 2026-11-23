#include <jni.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL Java_tvbox_fixture_NativeMath_add(JNIEnv *env, jclass type, jint left, jint right) {
    return (jint)((unsigned int)left + (unsigned int)right);
}

JNIEXPORT jint JNICALL Java_tvbox_fixture_NativeMath_nested(JNIEnv *env, jclass type, jint number) {
    jmethodID callback = (*env)->GetStaticMethodID(env, type, "callback", "(I)I");
    return (*env)->CallStaticIntMethod(env, type, callback, number);
}

JNIEXPORT jobject JNICALL Java_tvbox_fixture_NativeMath_proxyRoundTrip(JNIEnv *env, jclass type, jint value) {
    jmethodID constructor = (*env)->GetMethodID(env, type, "<init>", "(I)V");
    jobject instance = (*env)->NewObject(env, type, constructor, value);
    jfieldID field = (*env)->GetFieldID(env, type, "value", "I");
    jint initial = (*env)->GetIntField(env, instance, field);
    jmethodID method = (*env)->GetMethodID(env, type, "valuePlus", "(I)I");
    (*env)->SetIntField(env, instance, field, (*env)->CallIntMethod(env, instance, method, initial));
    return instance;
}

JNIEXPORT jobject JNICALL Java_tvbox_fixture_NativeMath_echo(JNIEnv *env, jclass type, jobject value) {
    return value;
}

JNIEXPORT jobject JNICALL Java_tvbox_fixture_NativeMath_tag(JNIEnv *env, jclass type, jobject value, jstring text) {
    return value ? text : 0;
}

JNIEXPORT jobjectArray JNICALL Java_tvbox_fixture_NativeMath_pair(JNIEnv *env, jclass type, jobject first, jobject second) {
    jclass component = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray output = (*env)->NewObjectArray(env, 2, component, first);
    (*env)->SetObjectArrayElement(env, output, 1, second);
    return output;
}

JNIEXPORT jobjectArray JNICALL Java_tvbox_fixture_NativeMath_strings(JNIEnv *env, jclass type, jobjectArray input) {
    jclass component = (*env)->FindClass(env, "java/lang/String");
    jobject text = (*env)->GetObjectArrayElement(env, input, 0);
    jobjectArray output = (*env)->NewObjectArray(env, 2, component, 0);
    (*env)->SetObjectArrayElement(env, output, 0, text);
    (*env)->SetObjectArrayElement(env, input, 1, text);
    return output;
}

JNIEXPORT jstring JNICALL Java_tvbox_fixture_NativeMath_loadType(JNIEnv *env, jclass type, jobject application, jstring name) {
    jclass context = (*env)->FindClass(env, "android/content/Context");
    jmethodID get_loader = (*env)->GetMethodID(env, context, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = (*env)->CallObjectMethod(env, application, get_loader);
    jclass loader_type = (*env)->GetObjectClass(env, loader);
    jmethodID load = (*env)->GetMethodID(env, loader_type, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jobject loaded = (*env)->CallObjectMethod(env, loader, load, name);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }
    jclass class_type = (*env)->FindClass(env, "java/lang/Class");
    jmethodID get_name = (*env)->GetMethodID(env, class_type, "getName", "()Ljava/lang/String;");
    return (*env)->CallObjectMethod(env, loaded, get_name);
}

JNIEXPORT jstring JNICALL Java_tvbox_fixture_NativeMath_unsupportedContextCall(JNIEnv *env, jclass type, jobject application) {
    jclass context = (*env)->FindClass(env, "android/content/Context");
    jmethodID get_path = (*env)->GetMethodID(env, context, "getPackageCodePath", "()Ljava/lang/String;");
    return (*env)->CallObjectMethod(env, application, get_path);
}

JNIEXPORT jstring JNICALL Java_tvbox_fixture_NativeMath_storageDirectory(JNIEnv *env, jclass type, jobject application, jstring name) {
    jclass context = (*env)->GetObjectClass(env, application);
    const char *method_name = (*env)->GetStringUTFChars(env, name, 0);
    jmethodID get_directory = (*env)->GetMethodID(env, context, method_name, "()Ljava/io/File;");
    (*env)->ReleaseStringUTFChars(env, name, method_name);
    jobject directory = (*env)->CallObjectMethod(env, application, get_directory);
    jclass file = (*env)->GetObjectClass(env, directory);
    jmethodID get_name = (*env)->GetMethodID(env, file, "getName", "()Ljava/lang/String;");
    return (*env)->CallObjectMethod(env, directory, get_name);
}
