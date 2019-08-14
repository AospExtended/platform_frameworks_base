/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "SurfaceControl"
#define LOG_NDEBUG 0

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android/graphics/Bitmap.h"
#include "android/graphics/GraphicsJNI.h"
#include "android/graphics/Region.h"
#include "core_jni_helpers.h"

#include <android-base/chrono_utils.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_view_SurfaceSession.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <jni.h>
#include <memory>
#include <stdio.h>
#include <system/graphics.h>
#include <ui/DisplayInfo.h>
#include <ui/FrameStats.h>
#include <ui/GraphicTypes.h>
#include <ui/HdrCapabilities.h>
#include <ui/Rect.h>
#include <ui/Region.h>
#include <utils/Log.h>

// ----------------------------------------------------------------------------

namespace android {

static const char* const OutOfResourcesException =
    "android/view/Surface$OutOfResourcesException";

static struct {
    jclass clazz;
    jmethodID ctor;
    jfieldID width;
    jfieldID height;
    jfieldID refreshRate;
    jfieldID density;
    jfieldID xDpi;
    jfieldID yDpi;
    jfieldID secure;
    jfieldID appVsyncOffsetNanos;
    jfieldID presentationDeadlineNanos;
} gPhysicalDisplayInfoClassInfo;

static struct {
    jfieldID bottom;
    jfieldID left;
    jfieldID right;
    jfieldID top;
} gRectClassInfo;

// Implements SkMallocPixelRef::ReleaseProc, to delete the screenshot on unref.
void DeleteScreenshot(void* addr, void* context) {
    delete ((ScreenshotClient*) context);
}

static struct {
    nsecs_t UNDEFINED_TIME_NANO;
    jmethodID init;
} gWindowContentFrameStatsClassInfo;

static struct {
    nsecs_t UNDEFINED_TIME_NANO;
    jmethodID init;
} gWindowAnimationFrameStatsClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gHdrCapabilitiesClassInfo;

static struct {
    jclass clazz;
    jmethodID builder;
} gGraphicBufferClassInfo;

// ----------------------------------------------------------------------------

static jlong nativeCreateTransaction(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(new SurfaceComposerClient::Transaction);
}

static void releaseTransaction(SurfaceComposerClient::Transaction* t) {
    delete t;
}

static jlong nativeGetNativeTransactionFinalizer(JNIEnv* env, jclass clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&releaseTransaction));
}

static jlong nativeCreate(JNIEnv* env, jclass clazz, jobject sessionObj,
        jstring nameStr, jint w, jint h, jint format, jint flags, jlong parentObject,
        jint windowType, jint ownerUid) {
    ScopedUtfChars name(env, nameStr);
    sp<SurfaceComposerClient> client(android_view_SurfaceSession_getClient(env, sessionObj));
    SurfaceControl *parent = reinterpret_cast<SurfaceControl*>(parentObject);
    sp<SurfaceControl> surface;
    status_t err = client->createSurfaceChecked(
            String8(name.c_str()), w, h, format, &surface, flags, parent, windowType, ownerUid);
    if (err == NAME_NOT_FOUND) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return 0;
    } else if (err != NO_ERROR) {
        jniThrowException(env, OutOfResourcesException, NULL);
        return 0;
    }

    surface->incStrong((void *)nativeCreate);
    return reinterpret_cast<jlong>(surface.get());
}

static void nativeRelease(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(nativeObject));
    ctrl->decStrong((void *)nativeCreate);
}

static void nativeDestroy(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(nativeObject));
    ctrl->clear();
    ctrl->decStrong((void *)nativeCreate);
}

static void nativeDisconnect(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    if (ctrl != NULL) {
        ctrl->disconnect();
    }
}

static Rect rectFromObj(JNIEnv* env, jobject rectObj) {
    int left = env->GetIntField(rectObj, gRectClassInfo.left);
    int top = env->GetIntField(rectObj, gRectClassInfo.top);
    int right = env->GetIntField(rectObj, gRectClassInfo.right);
    int bottom = env->GetIntField(rectObj, gRectClassInfo.bottom);
    return Rect(left, top, right, bottom);
}

static jobject nativeScreenshotToBuffer(JNIEnv* env, jclass clazz,
        jobject displayTokenObj, jobject sourceCropObj, jint width, jint height,
        jint minLayer, jint maxLayer, bool allLayers, bool useIdentityTransform,
        int rotation, bool captureSecureLayers) {
    sp<IBinder> displayToken = ibinderForJavaObject(env, displayTokenObj);
    if (displayToken == NULL) {
        return NULL;
    }
    Rect sourceCrop = rectFromObj(env, sourceCropObj);
    if (allLayers) {
        minLayer = INT32_MIN;
        maxLayer = INT32_MAX;
    }
    sp<GraphicBuffer> buffer;
    bool capturedSecureLayers = false;
    status_t res = ScreenshotClient::capture(displayToken,
            sourceCrop, width, height, minLayer, maxLayer, useIdentityTransform,
            rotation, captureSecureLayers, &buffer, capturedSecureLayers);
    if (res != NO_ERROR) {
        return NULL;
    }

    return env->CallStaticObjectMethod(gGraphicBufferClassInfo.clazz,
            gGraphicBufferClassInfo.builder,
            buffer->getWidth(),
            buffer->getHeight(),
            buffer->getPixelFormat(),
            (jint)buffer->getUsage(),
            (jlong)buffer.get(),
            capturedSecureLayers);
}

static jobject nativeScreenshotBitmap(JNIEnv* env, jclass clazz,
        jobject displayTokenObj, jobject sourceCropObj, jint width, jint height,
        jint minLayer, jint maxLayer, bool allLayers, bool useIdentityTransform,
        int rotation) {
    sp<IBinder> displayToken = ibinderForJavaObject(env, displayTokenObj);
    if (displayToken == NULL) {
        return NULL;
    }

    Rect sourceCrop = rectFromObj(env, sourceCropObj);

    std::unique_ptr<ScreenshotClient> screenshot(new ScreenshotClient());
    status_t res;
    if (allLayers) {
        minLayer = INT32_MIN;
        maxLayer = INT32_MAX;
    }

    sp<GraphicBuffer> buffer;
    res = ScreenshotClient::capture(displayToken, sourceCrop, width, height,
        minLayer, maxLayer, useIdentityTransform, static_cast<uint32_t>(rotation), &buffer);
    if (res != NO_ERROR) {
        return NULL;
    }

    SkColorType colorType;
    SkAlphaType alphaType;

    PixelFormat format = buffer->getPixelFormat();
    switch (format) {
        case PIXEL_FORMAT_RGBX_8888: {
            colorType = kRGBA_8888_SkColorType;
            alphaType = kOpaque_SkAlphaType;
            break;
        }
        case PIXEL_FORMAT_RGBA_8888: {
            colorType = kRGBA_8888_SkColorType;
            alphaType = kPremul_SkAlphaType;
            break;
        }
        case PIXEL_FORMAT_RGBA_FP16: {
            colorType = kRGBA_F16_SkColorType;
            alphaType = kPremul_SkAlphaType;
            break;
        }
        case PIXEL_FORMAT_RGB_565: {
            colorType = kRGB_565_SkColorType;
            alphaType = kOpaque_SkAlphaType;
            break;
        }
        default: {
            return NULL;
        }
    }

    SkImageInfo info = SkImageInfo::Make(buffer->getWidth(), buffer->getHeight(),
                                         colorType, alphaType,
                                         SkColorSpace::MakeSRGB());

    auto bitmap = sk_sp<Bitmap>(new Bitmap(buffer.get(), info));
    return bitmap::createBitmap(env, bitmap.release(),
                                android::bitmap::kBitmapCreateFlag_Premultiplied, NULL);
}

static void nativeScreenshot(JNIEnv* env, jclass clazz, jobject displayTokenObj,
        jobject surfaceObj, jobject sourceCropObj, jint width, jint height,
        jint minLayer, jint maxLayer, bool allLayers, bool useIdentityTransform) {
    sp<IBinder> displayToken = ibinderForJavaObject(env, displayTokenObj);
    if (displayToken == NULL) {
        return;
    }

    sp<Surface> consumer = android_view_Surface_getSurface(env, surfaceObj);
    if (consumer == NULL) {
        return;
    }

    Rect sourceCrop;
    if (sourceCropObj != NULL) {
        sourceCrop = rectFromObj(env, sourceCropObj);
    }

    if (allLayers) {
        minLayer = INT32_MIN;
        maxLayer = INT32_MAX;
    }

    sp<GraphicBuffer> buffer;
    ScreenshotClient::capture(displayToken, sourceCrop, width, height, minLayer, maxLayer,
                              useIdentityTransform, 0, &buffer);

    Surface::attachAndQueueBuffer(consumer.get(), buffer);
}

static jobject nativeCaptureLayers(JNIEnv* env, jclass clazz, jobject layerHandleToken,
        jobject sourceCropObj, jfloat frameScale) {

    sp<IBinder> layerHandle = ibinderForJavaObject(env, layerHandleToken);
    if (layerHandle == NULL) {
        return NULL;
    }

    Rect sourceCrop;
    if (sourceCropObj != NULL) {
        sourceCrop = rectFromObj(env, sourceCropObj);
    }

    sp<GraphicBuffer> buffer;
    status_t res = ScreenshotClient::captureChildLayers(layerHandle, sourceCrop, frameScale, &buffer);
    if (res != NO_ERROR) {
        return NULL;
    }

    return env->CallStaticObjectMethod(gGraphicBufferClassInfo.clazz,
                                       gGraphicBufferClassInfo.builder,
                                       buffer->getWidth(),
                                       buffer->getHeight(),
                                       buffer->getPixelFormat(),
                                       (jint)buffer->getUsage(),
                                       (jlong)buffer.get(),
                                       false /* capturedSecureLayers */);
}

static void nativeApplyTransaction(JNIEnv* env, jclass clazz, jlong transactionObj, jboolean sync) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->apply(sync);
}

static void nativeMergeTransaction(JNIEnv* env, jclass clazz,
        jlong transactionObj, jlong otherTransactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    auto otherTransaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(
            otherTransactionObj);
    transaction->merge(std::move(*otherTransaction));
}

static void nativeSetAnimationTransaction(JNIEnv* env, jclass clazz, jlong transactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->setAnimationTransaction();
}

static void nativeSetEarlyWakeup(JNIEnv* env, jclass clazz, jlong transactionObj) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    transaction->setEarlyWakeup();
}

static void nativeSetLayer(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint zorder) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setLayer(ctrl, zorder);
}

static void nativeSetRelativeLayer(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jobject relativeTo, jint zorder) {

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    sp<IBinder> handle = ibinderForJavaObject(env, relativeTo);

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setRelativeLayer(ctrl, handle, zorder);
    }
}

static void nativeSetPosition(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jfloat x, jfloat y) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setPosition(ctrl, x, y);
}

static void nativeSetGeometryAppliesWithResize(JNIEnv* env, jclass clazz,
jlong transactionObj,
        jlong nativeObject) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setGeometryAppliesWithResize(ctrl);
}

static void nativeSetSize(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint w, jint h) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setSize(ctrl, w, h);
}

static void nativeSetFlags(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint flags, jint mask) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setFlags(ctrl, flags, mask);
}

static void nativeSetTransparentRegionHint(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jobject regionObj) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    SkRegion* region = android_graphics_Region_getSkRegion(env, regionObj);
    if (!region) {
        doThrowIAE(env);
        return;
    }

    const SkIRect& b(region->getBounds());
    Region reg(Rect(b.fLeft, b.fTop, b.fRight, b.fBottom));
    if (region->isComplex()) {
        SkRegion::Iterator it(*region);
        while (!it.done()) {
            const SkIRect& r(it.rect());
            reg.addRectUnchecked(r.fLeft, r.fTop, r.fRight, r.fBottom);
            it.next();
        }
    }

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setTransparentRegionHint(ctrl, reg);
    }
}

static void nativeSetAlpha(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jfloat alpha) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setAlpha(ctrl, alpha);
}

static void nativeSetColor(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jfloatArray fColor) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);

    float* floatColors = env->GetFloatArrayElements(fColor, 0);
    half3 color(floatColors[0], floatColors[1], floatColors[2]);
    transaction->setColor(ctrl, color);
}

static void nativeSetMatrix(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jfloat dsdx, jfloat dtdx, jfloat dtdy, jfloat dsdy) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setMatrix(ctrl, dsdx, dtdx, dtdy, dsdy);
}

static void nativeSetWindowCrop(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jint l, jint t, jint r, jint b) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    Rect crop(l, t, r, b);
    transaction->setCrop(ctrl, crop);
}

static void nativeSetFinalCrop(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jint l, jint t, jint r, jint b) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    Rect crop(l, t, r, b);
    transaction->setFinalCrop(ctrl, crop);
}

static void nativeSetLayerStack(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject, jint layerStack) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setLayerStack(ctrl, layerStack);
}

static jobject nativeGetBuiltInDisplay(JNIEnv* env, jclass clazz, jint id) {
    sp<IBinder> token(SurfaceComposerClient::getBuiltInDisplay(id));
    return javaObjectForIBinder(env, token);
}

static jobject nativeCreateDisplay(JNIEnv* env, jclass clazz, jstring nameObj,
        jboolean secure) {
    ScopedUtfChars name(env, nameObj);
    sp<IBinder> token(SurfaceComposerClient::createDisplay(
            String8(name.c_str()), bool(secure)));
    return javaObjectForIBinder(env, token);
}

static void nativeDestroyDisplay(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    SurfaceComposerClient::destroyDisplay(token);
}

static void nativeSetDisplaySurface(JNIEnv* env, jclass clazz,
        jlong transactionObj,
        jobject tokenObj, jlong nativeSurfaceObject) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    sp<IGraphicBufferProducer> bufferProducer;
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeSurfaceObject));
    if (sur != NULL) {
        bufferProducer = sur->getIGraphicBufferProducer();
    }


    status_t err = NO_ERROR;
    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        err = transaction->setDisplaySurface(token,
                bufferProducer);
    }
    if (err != NO_ERROR) {
        doThrowIAE(env, "Illegal Surface, could not enable async mode. Was this"
                " Surface created with singleBufferMode?");
    }
}

static void nativeSetDisplayLayerStack(JNIEnv* env, jclass clazz,
        jlong transactionObj,
        jobject tokenObj, jint layerStack) {

    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setDisplayLayerStack(token, layerStack);
    }
}

static void nativeSetDisplayProjection(JNIEnv* env, jclass clazz,
        jlong transactionObj,
        jobject tokenObj, jint orientation,
        jint layerStackRect_left, jint layerStackRect_top, jint layerStackRect_right, jint layerStackRect_bottom,
        jint displayRect_left, jint displayRect_top, jint displayRect_right, jint displayRect_bottom) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    Rect layerStackRect(layerStackRect_left, layerStackRect_top, layerStackRect_right, layerStackRect_bottom);
    Rect displayRect(displayRect_left, displayRect_top, displayRect_right, displayRect_bottom);

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setDisplayProjection(token, orientation, layerStackRect, displayRect);
    }
}

static void nativeSetDisplaySize(JNIEnv* env, jclass clazz,
        jlong transactionObj,
        jobject tokenObj, jint width, jint height) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->setDisplaySize(token, width, height);
    }
}

static jobjectArray nativeGetDisplayConfigs(JNIEnv* env, jclass clazz,
        jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return NULL;

    Vector<DisplayInfo> configs;
    if (SurfaceComposerClient::getDisplayConfigs(token, &configs) != NO_ERROR ||
            configs.size() == 0) {
        return NULL;
    }

    jobjectArray configArray = env->NewObjectArray(configs.size(),
            gPhysicalDisplayInfoClassInfo.clazz, NULL);

    for (size_t c = 0; c < configs.size(); ++c) {
        const DisplayInfo& info = configs[c];
        jobject infoObj = env->NewObject(gPhysicalDisplayInfoClassInfo.clazz,
                gPhysicalDisplayInfoClassInfo.ctor);
        env->SetIntField(infoObj, gPhysicalDisplayInfoClassInfo.width, info.w);
        env->SetIntField(infoObj, gPhysicalDisplayInfoClassInfo.height, info.h);
        env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.refreshRate, info.fps);
        env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.density, info.density);
        env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.xDpi, info.xdpi);
        env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.yDpi, info.ydpi);
        env->SetBooleanField(infoObj, gPhysicalDisplayInfoClassInfo.secure, info.secure);
        env->SetLongField(infoObj, gPhysicalDisplayInfoClassInfo.appVsyncOffsetNanos,
                info.appVsyncOffset);
        env->SetLongField(infoObj, gPhysicalDisplayInfoClassInfo.presentationDeadlineNanos,
                info.presentationDeadline);
        env->SetObjectArrayElement(configArray, static_cast<jsize>(c), infoObj);
        env->DeleteLocalRef(infoObj);
    }

    return configArray;
}

static jint nativeGetActiveConfig(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return -1;
    return static_cast<jint>(SurfaceComposerClient::getActiveConfig(token));
}

static jboolean nativeSetActiveConfig(JNIEnv* env, jclass clazz, jobject tokenObj, jint id) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return JNI_FALSE;
    status_t err = SurfaceComposerClient::setActiveConfig(token, static_cast<int>(id));
    return err == NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

static jintArray nativeGetDisplayColorModes(JNIEnv* env, jclass, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return NULL;
    Vector<ui::ColorMode> colorModes;
    if (SurfaceComposerClient::getDisplayColorModes(token, &colorModes) != NO_ERROR ||
            colorModes.isEmpty()) {
        return NULL;
    }

    jintArray colorModesArray = env->NewIntArray(colorModes.size());
    if (colorModesArray == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", NULL);
        return NULL;
    }
    jint* colorModesArrayValues = env->GetIntArrayElements(colorModesArray, 0);
    for (size_t i = 0; i < colorModes.size(); i++) {
        colorModesArrayValues[i] = static_cast<jint>(colorModes[i]);
    }
    env->ReleaseIntArrayElements(colorModesArray, colorModesArrayValues, 0);
    return colorModesArray;
}

static jint nativeGetActiveColorMode(JNIEnv* env, jclass, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return -1;
    return static_cast<jint>(SurfaceComposerClient::getActiveColorMode(token));
}

static jboolean nativeSetActiveColorMode(JNIEnv* env, jclass,
        jobject tokenObj, jint colorMode) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return JNI_FALSE;
    status_t err = SurfaceComposerClient::setActiveColorMode(token,
            static_cast<ui::ColorMode>(colorMode));
    return err == NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

static void nativeSetDisplayPowerMode(JNIEnv* env, jclass clazz, jobject tokenObj, jint mode) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    android::base::Timer t;
    SurfaceComposerClient::setDisplayPowerMode(token, mode);
    if (t.duration() > 100ms) ALOGD("Excessive delay in setPowerMode()");
}

static jboolean nativeClearContentFrameStats(JNIEnv* env, jclass clazz, jlong nativeObject) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->clearLayerFrameStats();

    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }

    // The other end is not ready, just report we failed.
    if (err == NO_INIT) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean nativeGetContentFrameStats(JNIEnv* env, jclass clazz, jlong nativeObject,
    jobject outStats) {
    FrameStats stats;

    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->getLayerFrameStats(&stats);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }

    // The other end is not ready, fine just return empty stats.
    if (err == NO_INIT) {
        return JNI_FALSE;
    }

    jlong refreshPeriodNano = static_cast<jlong>(stats.refreshPeriodNano);
    size_t frameCount = stats.desiredPresentTimesNano.size();

    jlongArray postedTimesNanoDst = env->NewLongArray(frameCount);
    if (postedTimesNanoDst == NULL) {
        return JNI_FALSE;
    }

    jlongArray presentedTimesNanoDst = env->NewLongArray(frameCount);
    if (presentedTimesNanoDst == NULL) {
        return JNI_FALSE;
    }

    jlongArray readyTimesNanoDst = env->NewLongArray(frameCount);
    if (readyTimesNanoDst == NULL) {
        return JNI_FALSE;
    }

    nsecs_t postedTimesNanoSrc[frameCount];
    nsecs_t presentedTimesNanoSrc[frameCount];
    nsecs_t readyTimesNanoSrc[frameCount];

    for (size_t i = 0; i < frameCount; i++) {
        nsecs_t postedTimeNano = stats.desiredPresentTimesNano[i];
        if (postedTimeNano == INT64_MAX) {
            postedTimeNano = gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO;
        }
        postedTimesNanoSrc[i] = postedTimeNano;

        nsecs_t presentedTimeNano = stats.actualPresentTimesNano[i];
        if (presentedTimeNano == INT64_MAX) {
            presentedTimeNano = gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO;
        }
        presentedTimesNanoSrc[i] = presentedTimeNano;

        nsecs_t readyTimeNano = stats.frameReadyTimesNano[i];
        if (readyTimeNano == INT64_MAX) {
            readyTimeNano = gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO;
        }
        readyTimesNanoSrc[i] = readyTimeNano;
    }

    env->SetLongArrayRegion(postedTimesNanoDst, 0, frameCount, postedTimesNanoSrc);
    env->SetLongArrayRegion(presentedTimesNanoDst, 0, frameCount, presentedTimesNanoSrc);
    env->SetLongArrayRegion(readyTimesNanoDst, 0, frameCount, readyTimesNanoSrc);

    env->CallVoidMethod(outStats, gWindowContentFrameStatsClassInfo.init, refreshPeriodNano,
            postedTimesNanoDst, presentedTimesNanoDst, readyTimesNanoDst);

    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean nativeClearAnimationFrameStats(JNIEnv* env, jclass clazz) {
    status_t err = SurfaceComposerClient::clearAnimationFrameStats();

    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }

    // The other end is not ready, just report we failed.
    if (err == NO_INIT) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean nativeGetAnimationFrameStats(JNIEnv* env, jclass clazz, jobject outStats) {
    FrameStats stats;

    status_t err = SurfaceComposerClient::getAnimationFrameStats(&stats);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }

    // The other end is not ready, fine just return empty stats.
    if (err == NO_INIT) {
        return JNI_FALSE;
    }

    jlong refreshPeriodNano = static_cast<jlong>(stats.refreshPeriodNano);
    size_t frameCount = stats.desiredPresentTimesNano.size();

    jlongArray presentedTimesNanoDst = env->NewLongArray(frameCount);
    if (presentedTimesNanoDst == NULL) {
        return JNI_FALSE;
    }

    nsecs_t presentedTimesNanoSrc[frameCount];

    for (size_t i = 0; i < frameCount; i++) {
        nsecs_t presentedTimeNano = stats.actualPresentTimesNano[i];
        if (presentedTimeNano == INT64_MAX) {
            presentedTimeNano = gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO;
        }
        presentedTimesNanoSrc[i] = presentedTimeNano;
    }

    env->SetLongArrayRegion(presentedTimesNanoDst, 0, frameCount, presentedTimesNanoSrc);

    env->CallVoidMethod(outStats, gWindowAnimationFrameStatsClassInfo.init, refreshPeriodNano,
            presentedTimesNanoDst);

    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static void nativeDeferTransactionUntil(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jobject handleObject, jlong frameNumber) {
    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    sp<IBinder> handle = ibinderForJavaObject(env, handleObject);

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->deferTransactionUntil(ctrl, handle, frameNumber);
    }
}

static void nativeDeferTransactionUntilSurface(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jlong surfaceObject, jlong frameNumber) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    sp<Surface> barrier = reinterpret_cast<Surface *>(surfaceObject);

    transaction->deferTransactionUntil(ctrl, barrier, frameNumber);
}

static void nativeReparentChildren(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jobject newParentObject) {

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    sp<IBinder> handle = ibinderForJavaObject(env, newParentObject);

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->reparentChildren(ctrl, handle);
    }
}

static void nativeReparent(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jobject newParentObject) {
    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    sp<IBinder> parentHandle = ibinderForJavaObject(env, newParentObject);

    {
        auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
        transaction->reparent(ctrl, parentHandle);
    }
}

static void nativeSeverChildren(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->detachChildren(ctrl);
}

static void nativeSetOverrideScalingMode(JNIEnv* env, jclass clazz, jlong transactionObj,
        jlong nativeObject,
        jint scalingMode) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);

    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    transaction->setOverrideScalingMode(ctrl, scalingMode);
}

static void nativeDestroyInTransaction(JNIEnv* env, jclass clazz,
                                       jlong transactionObj,
                                       jlong nativeObject) {
    auto transaction = reinterpret_cast<SurfaceComposerClient::Transaction*>(transactionObj);
    auto ctrl = reinterpret_cast<SurfaceControl*>(nativeObject);
    transaction->destroySurface(ctrl);
}

static jobject nativeGetHandle(JNIEnv* env, jclass clazz, jlong nativeObject) {
    auto ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    return javaObjectForIBinder(env, ctrl->getHandle());
}

static jobject nativeGetHdrCapabilities(JNIEnv* env, jclass clazz, jobject tokenObject) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObject));
    if (token == NULL) return NULL;

    HdrCapabilities capabilities;
    SurfaceComposerClient::getHdrCapabilities(token, &capabilities);

    const auto& types = capabilities.getSupportedHdrTypes();
    std::vector<int32_t> intTypes;
    for (auto type : types) {
        intTypes.push_back(static_cast<int32_t>(type));
    }
    auto typesArray = env->NewIntArray(types.size());
    env->SetIntArrayRegion(typesArray, 0, intTypes.size(), intTypes.data());

    return env->NewObject(gHdrCapabilitiesClassInfo.clazz, gHdrCapabilitiesClassInfo.ctor,
            typesArray, capabilities.getDesiredMaxLuminance(),
            capabilities.getDesiredMaxAverageLuminance(), capabilities.getDesiredMinLuminance());
}

static jlong nativeReadFromParcel(JNIEnv* env, jclass clazz, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return 0;
    }
    sp<SurfaceControl> surface = SurfaceControl::readFromParcel(parcel);
    if (surface == nullptr) {
        return 0;
    }
    surface->incStrong((void *)nativeCreate);
    return reinterpret_cast<jlong>(surface.get());
}

static void nativeWriteToParcel(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return;
    }
    SurfaceControl* const self = reinterpret_cast<SurfaceControl *>(nativeObject);
    self->writeToParcel(parcel);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod sSurfaceControlMethods[] = {
    {"nativeCreate", "(Landroid/view/SurfaceSession;Ljava/lang/String;IIIIJII)J",
            (void*)nativeCreate },
    {"nativeReadFromParcel", "(Landroid/os/Parcel;)J",
            (void*)nativeReadFromParcel },
    {"nativeWriteToParcel", "(JLandroid/os/Parcel;)V",
            (void*)nativeWriteToParcel },
    {"nativeRelease", "(J)V",
            (void*)nativeRelease },
    {"nativeDestroy", "(J)V",
            (void*)nativeDestroy },
    {"nativeDisconnect", "(J)V",
            (void*)nativeDisconnect },
    {"nativeScreenshot", "(Landroid/os/IBinder;Landroid/graphics/Rect;IIIIZZI)Landroid/graphics/Bitmap;",
            (void*)nativeScreenshotBitmap },
    {"nativeScreenshot", "(Landroid/os/IBinder;Landroid/view/Surface;Landroid/graphics/Rect;IIIIZZ)V",
            (void*)nativeScreenshot },
    {"nativeCreateTransaction", "()J",
            (void*)nativeCreateTransaction },
    {"nativeApplyTransaction", "(JZ)V",
            (void*)nativeApplyTransaction },
    {"nativeGetNativeTransactionFinalizer", "()J",
            (void*)nativeGetNativeTransactionFinalizer },
    {"nativeMergeTransaction", "(JJ)V",
            (void*)nativeMergeTransaction },
    {"nativeSetAnimationTransaction", "(J)V",
            (void*)nativeSetAnimationTransaction },
    {"nativeSetEarlyWakeup", "(J)V",
            (void*)nativeSetEarlyWakeup },
    {"nativeSetLayer", "(JJI)V",
            (void*)nativeSetLayer },
    {"nativeSetRelativeLayer", "(JJLandroid/os/IBinder;I)V",
            (void*)nativeSetRelativeLayer },
    {"nativeSetPosition", "(JJFF)V",
            (void*)nativeSetPosition },
    {"nativeSetGeometryAppliesWithResize", "(JJ)V",
            (void*)nativeSetGeometryAppliesWithResize },
    {"nativeSetSize", "(JJII)V",
            (void*)nativeSetSize },
    {"nativeSetTransparentRegionHint", "(JJLandroid/graphics/Region;)V",
            (void*)nativeSetTransparentRegionHint },
    {"nativeSetAlpha", "(JJF)V",
            (void*)nativeSetAlpha },
    {"nativeSetColor", "(JJ[F)V",
            (void*)nativeSetColor },
    {"nativeSetMatrix", "(JJFFFF)V",
            (void*)nativeSetMatrix },
    {"nativeSetFlags", "(JJII)V",
            (void*)nativeSetFlags },
    {"nativeSetWindowCrop", "(JJIIII)V",
            (void*)nativeSetWindowCrop },
    {"nativeSetFinalCrop", "(JJIIII)V",
            (void*)nativeSetFinalCrop },
    {"nativeSetLayerStack", "(JJI)V",
            (void*)nativeSetLayerStack },
    {"nativeGetBuiltInDisplay", "(I)Landroid/os/IBinder;",
            (void*)nativeGetBuiltInDisplay },
    {"nativeCreateDisplay", "(Ljava/lang/String;Z)Landroid/os/IBinder;",
            (void*)nativeCreateDisplay },
    {"nativeDestroyDisplay", "(Landroid/os/IBinder;)V",
            (void*)nativeDestroyDisplay },
    {"nativeSetDisplaySurface", "(JLandroid/os/IBinder;J)V",
            (void*)nativeSetDisplaySurface },
    {"nativeSetDisplayLayerStack", "(JLandroid/os/IBinder;I)V",
            (void*)nativeSetDisplayLayerStack },
    {"nativeSetDisplayProjection", "(JLandroid/os/IBinder;IIIIIIIII)V",
            (void*)nativeSetDisplayProjection },
    {"nativeSetDisplaySize", "(JLandroid/os/IBinder;II)V",
            (void*)nativeSetDisplaySize },
    {"nativeGetDisplayConfigs", "(Landroid/os/IBinder;)[Landroid/view/SurfaceControl$PhysicalDisplayInfo;",
            (void*)nativeGetDisplayConfigs },
    {"nativeGetActiveConfig", "(Landroid/os/IBinder;)I",
            (void*)nativeGetActiveConfig },
    {"nativeSetActiveConfig", "(Landroid/os/IBinder;I)Z",
            (void*)nativeSetActiveConfig },
    {"nativeGetDisplayColorModes", "(Landroid/os/IBinder;)[I",
            (void*)nativeGetDisplayColorModes},
    {"nativeGetActiveColorMode", "(Landroid/os/IBinder;)I",
            (void*)nativeGetActiveColorMode},
    {"nativeSetActiveColorMode", "(Landroid/os/IBinder;I)Z",
            (void*)nativeSetActiveColorMode},
    {"nativeGetHdrCapabilities", "(Landroid/os/IBinder;)Landroid/view/Display$HdrCapabilities;",
            (void*)nativeGetHdrCapabilities },
    {"nativeClearContentFrameStats", "(J)Z",
            (void*)nativeClearContentFrameStats },
    {"nativeGetContentFrameStats", "(JLandroid/view/WindowContentFrameStats;)Z",
            (void*)nativeGetContentFrameStats },
    {"nativeClearAnimationFrameStats", "()Z",
            (void*)nativeClearAnimationFrameStats },
    {"nativeGetAnimationFrameStats", "(Landroid/view/WindowAnimationFrameStats;)Z",
            (void*)nativeGetAnimationFrameStats },
    {"nativeSetDisplayPowerMode", "(Landroid/os/IBinder;I)V",
            (void*)nativeSetDisplayPowerMode },
    {"nativeDeferTransactionUntil", "(JJLandroid/os/IBinder;J)V",
            (void*)nativeDeferTransactionUntil },
    {"nativeDeferTransactionUntilSurface", "(JJJJ)V",
            (void*)nativeDeferTransactionUntilSurface },
    {"nativeReparentChildren", "(JJLandroid/os/IBinder;)V",
            (void*)nativeReparentChildren } ,
    {"nativeReparent", "(JJLandroid/os/IBinder;)V",
            (void*)nativeReparent },
    {"nativeSeverChildren", "(JJ)V",
            (void*)nativeSeverChildren } ,
    {"nativeSetOverrideScalingMode", "(JJI)V",
            (void*)nativeSetOverrideScalingMode },
    {"nativeDestroy", "(JJ)V",
            (void*)nativeDestroyInTransaction },
    {"nativeGetHandle", "(J)Landroid/os/IBinder;",
            (void*)nativeGetHandle },
    {"nativeScreenshotToBuffer",
     "(Landroid/os/IBinder;Landroid/graphics/Rect;IIIIZZIZ)Landroid/graphics/GraphicBuffer;",
     (void*)nativeScreenshotToBuffer },
    {"nativeCaptureLayers", "(Landroid/os/IBinder;Landroid/graphics/Rect;F)Landroid/graphics/GraphicBuffer;",
            (void*)nativeCaptureLayers },
};

int register_android_view_SurfaceControl(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/view/SurfaceControl",
            sSurfaceControlMethods, NELEM(sSurfaceControlMethods));

    jclass clazz = FindClassOrDie(env, "android/view/SurfaceControl$PhysicalDisplayInfo");
    gPhysicalDisplayInfoClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gPhysicalDisplayInfoClassInfo.ctor = GetMethodIDOrDie(env,
            gPhysicalDisplayInfoClassInfo.clazz, "<init>", "()V");
    gPhysicalDisplayInfoClassInfo.width =       GetFieldIDOrDie(env, clazz, "width", "I");
    gPhysicalDisplayInfoClassInfo.height =      GetFieldIDOrDie(env, clazz, "height", "I");
    gPhysicalDisplayInfoClassInfo.refreshRate = GetFieldIDOrDie(env, clazz, "refreshRate", "F");
    gPhysicalDisplayInfoClassInfo.density =     GetFieldIDOrDie(env, clazz, "density", "F");
    gPhysicalDisplayInfoClassInfo.xDpi =        GetFieldIDOrDie(env, clazz, "xDpi", "F");
    gPhysicalDisplayInfoClassInfo.yDpi =        GetFieldIDOrDie(env, clazz, "yDpi", "F");
    gPhysicalDisplayInfoClassInfo.secure =      GetFieldIDOrDie(env, clazz, "secure", "Z");
    gPhysicalDisplayInfoClassInfo.appVsyncOffsetNanos = GetFieldIDOrDie(env,
            clazz, "appVsyncOffsetNanos", "J");
    gPhysicalDisplayInfoClassInfo.presentationDeadlineNanos = GetFieldIDOrDie(env,
            clazz, "presentationDeadlineNanos", "J");

    jclass rectClazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.bottom = GetFieldIDOrDie(env, rectClazz, "bottom", "I");
    gRectClassInfo.left =   GetFieldIDOrDie(env, rectClazz, "left", "I");
    gRectClassInfo.right =  GetFieldIDOrDie(env, rectClazz, "right", "I");
    gRectClassInfo.top =    GetFieldIDOrDie(env, rectClazz, "top", "I");

    jclass frameStatsClazz = FindClassOrDie(env, "android/view/FrameStats");
    jfieldID undefined_time_nano_field = GetStaticFieldIDOrDie(env,
            frameStatsClazz, "UNDEFINED_TIME_NANO", "J");
    nsecs_t undefined_time_nano = env->GetStaticLongField(frameStatsClazz, undefined_time_nano_field);

    jclass contFrameStatsClazz = FindClassOrDie(env, "android/view/WindowContentFrameStats");
    gWindowContentFrameStatsClassInfo.init = GetMethodIDOrDie(env,
            contFrameStatsClazz, "init", "(J[J[J[J)V");
    gWindowContentFrameStatsClassInfo.UNDEFINED_TIME_NANO = undefined_time_nano;

    jclass animFrameStatsClazz = FindClassOrDie(env, "android/view/WindowAnimationFrameStats");
    gWindowAnimationFrameStatsClassInfo.init =  GetMethodIDOrDie(env,
            animFrameStatsClazz, "init", "(J[J)V");
    gWindowAnimationFrameStatsClassInfo.UNDEFINED_TIME_NANO = undefined_time_nano;

    jclass hdrCapabilitiesClazz = FindClassOrDie(env, "android/view/Display$HdrCapabilities");
    gHdrCapabilitiesClassInfo.clazz = MakeGlobalRefOrDie(env, hdrCapabilitiesClazz);
    gHdrCapabilitiesClassInfo.ctor = GetMethodIDOrDie(env, hdrCapabilitiesClazz, "<init>",
            "([IFFF)V");

    jclass graphicsBufferClazz = FindClassOrDie(env, "android/graphics/GraphicBuffer");
    gGraphicBufferClassInfo.clazz = MakeGlobalRefOrDie(env, graphicsBufferClazz);
    gGraphicBufferClassInfo.builder = GetStaticMethodIDOrDie(env, graphicsBufferClazz,
            "createFromExisting", "(IIIIJZ)Landroid/graphics/GraphicBuffer;");

    return err;
}

};
