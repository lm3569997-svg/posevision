// PoseVision ncnn JNI 桥：YOLO11n-pose
//
// 输入：Java 侧 letterbox 成 640x640 的 ARGB_8888 Bitmap
// 输出：out0，形状 [56, 8400]，通道优先

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "net.h"

#define LOG_TAG "PoseVisionJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static ncnn::Net *g_net = nullptr;

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_poseguard_x_PoseEngine_nativeInit(
        JNIEnv *env, jobject, jobject assetManager,
        jstring paramName, jstring binName) {

    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }

    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) { LOGE("AAssetManager null"); return -1; }

    std::string param = jstr(env, paramName);
    std::string bin = jstr(env, binName);

    AAsset *probe = AAssetManager_open(mgr, param.c_str(), AASSET_MODE_UNKNOWN);
    if (!probe) { LOGE("param 缺失: %s", param.c_str()); return -2; }
    AAsset_close(probe);
    probe = AAssetManager_open(mgr, bin.c_str(), AASSET_MODE_UNKNOWN);
    if (!probe) { LOGE("bin 缺失: %s", bin.c_str()); return -3; }
    AAsset_close(probe);

    ncnn::Net *net = new ncnn::Net();
    net->opt.num_threads = 4;
    net->opt.use_vulkan_compute = false;
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.use_fp16_arithmetic = true;

    int ret = net->load_param(mgr, param.c_str());
    if (ret != 0) { LOGE("load_param %d", ret); delete net; return -4; }

    ret = net->load_model(mgr, bin.c_str());
    if (ret != 0) { LOGE("load_model %d", ret); delete net; return -5; }

    g_net = net;
    LOGI("YOLO11 加载完成");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_poseguard_x_PoseEngine_nativeDetect(
        JNIEnv *env, jobject, jobject bitmap, jfloatArray outArr,
        jstring inputName, jstring outputName) {

    if (!g_net) return -1;
    if (!bitmap || !outArr) return -2;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return -3;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("格式非 RGBA_8888: %d", info.format);
        return -4;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -5;

    ncnn::Mat in = ncnn::Mat::from_pixels(
            reinterpret_cast<const unsigned char *>(pixels),
            ncnn::Mat::PIXEL_RGBA2RGB,
            static_cast<int>(info.width),
            static_cast<int>(info.height));

    AndroidBitmap_unlockPixels(env, bitmap);
    if (in.empty()) return -6;

    std::string inName = jstr(env, inputName);
    std::string outName = jstr(env, outputName);

    ncnn::Extractor ex = g_net->create_extractor();
    ex.set_light_mode(true);

    int ret = ex.input(inName.c_str(), in);
    if (ret != 0) { LOGE("input(%s) %d", inName.c_str(), ret); return -10; }

    ncnn::Mat out;
    ret = ex.extract(outName.c_str(), out);
    if (ret != 0) { LOGE("extract(%s) %d", outName.c_str(), ret); return -11; }

    ncnn::Mat flat;
    if (out.elempack != 1) {
        ncnn::convert_packing(out, flat, 1);
    } else {
        flat = out;
    }

    const int total = static_cast<int>(flat.total());
    const int arrLen = static_cast<int>(env->GetArrayLength(outArr));
    if (arrLen < total) { LOGE("缓冲不足 %d < %d", arrLen, total); return -12; }

    env->SetFloatArrayRegion(outArr, 0, total, reinterpret_cast<const jfloat *>(flat.data));
    return total;
}

extern "C" JNIEXPORT void JNICALL
Java_com_poseguard_x_PoseEngine_nativeRelease(JNIEnv *, jobject) {
    if (g_net) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
}
