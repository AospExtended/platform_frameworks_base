/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "Surface"

#include <stdio.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <android/graphics/canvas.h>
#include <android_runtime/android_graphics_GraphicBuffer.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/Log.h>
#include <private/android/AHardwareBufferHelpers.h>

#include "android_os_Parcel.h"
#include <binder/Parcel.h>

#include <gui/Surface.h>
#include <gui/view/Surface.h>
#include <gui/SurfaceControl.h>

#include <ui/GraphicBuffer.h>
#include <ui/Rect.h>
#include <ui/Region.h>

#include <utils/misc.h>
#include <utils/Log.h>

#include <nativehelper/ScopedUtfChars.h>

// ----------------------------------------------------------------------------

namespace android {

static const char* const IllegalArgumentException = "java/lang/IllegalArgumentException";
static const char* const OutOfResourcesException = "android/view/Surface$OutOfResourcesException";

static struct {
    jclass clazz;
    jfieldID mNativeObject;
    jfieldID mLock;
    jmethodID ctor;
} gSurfaceClassInfo;

static struct {
    jfieldID left;
    jfieldID top;
    jfieldID right;
    jfieldID bottom;
} gRectClassInfo;

class JNamedColorSpace {
public:
    // ColorSpace.Named.SRGB.ordinal() = 0;
    static constexpr jint SRGB = 0;

    // ColorSpace.Named.DISPLAY_P3.ordinal() = 7;
    static constexpr jint DISPLAY_P3 = 7;
};

constexpr ui::Dataspace fromNamedColorSpaceValueToDataspace(const jint colorSpace) {
    switch (colorSpace) {
        case JNamedColorSpace::DISPLAY_P3:
            return ui::Dataspace::DISPLAY_P3;
        default:
            return ui::Dataspace::V0_SRGB;
    }
}

// ----------------------------------------------------------------------------

// this is just a pointer we use to pass to inc/decStrong
static const void *sRefBaseOwner;

bool android_view_Surface_isInstanceOf(JNIEnv* env, jobject obj) {
    return env->IsInstanceOf(obj, gSurfaceClassInfo.clazz);
}

sp<ANativeWindow> android_view_Surface_getNativeWindow(JNIEnv* env, jobject surfaceObj) {
    return android_view_Surface_getSurface(env, surfaceObj);
}

sp<Surface> android_view_Surface_getSurface(JNIEnv* env, jobject surfaceObj) {
    sp<Surface> sur;
    jobject lock = env->GetObjectField(surfaceObj,
            gSurfaceClassInfo.mLock);
    if (env->MonitorEnter(lock) == JNI_OK) {
        sur = reinterpret_cast<Surface *>(
                env->GetLongField(surfaceObj, gSurfaceClassInfo.mNativeObject));
        env->MonitorExit(lock);
    }
    env->DeleteLocalRef(lock);
    return sur;
}

jobject android_view_Surface_createFromSurface(JNIEnv* env, const sp<Surface>& surface) {
    jobject surfaceObj = env->NewObject(gSurfaceClassInfo.clazz,
            gSurfaceClassInfo.ctor, (jlong)surface.get());
    if (surfaceObj == NULL) {
        if (env->ExceptionCheck()) {
            ALOGE("Could not create instance of Surface from IGraphicBufferProducer.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        return NULL;
    }
    surface->incStrong(&sRefBaseOwner);
    return surfaceObj;
}

jobject android_view_Surface_createFromIGraphicBufferProducer(JNIEnv* env,
        const sp<IGraphicBufferProducer>& bufferProducer) {
    if (bufferProducer == NULL) {
        return NULL;
    }

    sp<Surface> surface(new Surface(bufferProducer, true));
    return android_view_Surface_createFromSurface(env, surface);
}

// ----------------------------------------------------------------------------

static inline bool isSurfaceValid(const sp<Surface>& sur) {
    return Surface::isValid(sur);
}

// ----------------------------------------------------------------------------

static jlong nativeCreateFromSurfaceTexture(JNIEnv* env, jclass clazz,
        jobject surfaceTextureObj) {
    sp<IGraphicBufferProducer> producer(SurfaceTexture_getProducer(env, surfaceTextureObj));
    if (producer == NULL) {
        jniThrowException(env, IllegalArgumentException,
                "SurfaceTexture has already been released");
        return 0;
    }

    sp<Surface> surface(new Surface(producer, true));
    if (surface == NULL) {
        jniThrowException(env, OutOfResourcesException, NULL);
        return 0;
    }

    surface->incStrong(&sRefBaseOwner);
    return jlong(surface.get());
}

static void nativeRelease(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeObject));
    sur->decStrong(&sRefBaseOwner);
}

static jboolean nativeIsValid(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeObject));
    return isSurfaceValid(sur) ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeIsConsumerRunningBehind(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeObject));
    if (!isSurfaceValid(sur)) {
        jniThrowException(env, IllegalArgumentException, NULL);
        return JNI_FALSE;
    }
    int value = 0;
    ANativeWindow* anw = static_cast<ANativeWindow*>(sur.get());
    anw->query(anw, NATIVE_WINDOW_CONSUMER_RUNNING_BEHIND, &value);
    return value;
}

static jlong nativeLockCanvas(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject canvasObj, jobject dirtyRectObj) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));

    if (!isSurfaceValid(surface)) {
        jniThrowException(env, IllegalArgumentException, NULL);
        return 0;
    }

    if (!ACanvas_isSupportedPixelFormat(ANativeWindow_getFormat(surface.get()))) {
        native_window_set_buffers_format(surface.get(), PIXEL_FORMAT_RGBA_8888);
    }

    Rect dirtyRect(Rect::EMPTY_RECT);
    Rect* dirtyRectPtr = NULL;

    if (dirtyRectObj) {
        dirtyRect.left   = env->GetIntField(dirtyRectObj, gRectClassInfo.left);
        dirtyRect.top    = env->GetIntField(dirtyRectObj, gRectClassInfo.top);
        dirtyRect.right  = env->GetIntField(dirtyRectObj, gRectClassInfo.right);
        dirtyRect.bottom = env->GetIntField(dirtyRectObj, gRectClassInfo.bottom);
        dirtyRectPtr = &dirtyRect;
    }

    ANativeWindow_Buffer buffer;
    status_t err = surface->lock(&buffer, dirtyRectPtr);
    if (err < 0) {
        const char* const exception = (err == NO_MEMORY) ?
                OutOfResourcesException : IllegalArgumentException;
        jniThrowException(env, exception, NULL);
        return 0;
    }

    graphics::Canvas canvas(env, canvasObj);
    canvas.setBuffer(&buffer, static_cast<int32_t>(surface->getBuffersDataSpace()));

    if (dirtyRectPtr) {
        canvas.clipRect({dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom});
    }

    if (dirtyRectObj) {
        env->SetIntField(dirtyRectObj, gRectClassInfo.left,   dirtyRect.left);
        env->SetIntField(dirtyRectObj, gRectClassInfo.top,    dirtyRect.top);
        env->SetIntField(dirtyRectObj, gRectClassInfo.right,  dirtyRect.right);
        env->SetIntField(dirtyRectObj, gRectClassInfo.bottom, dirtyRect.bottom);
    }

    // Create another reference to the surface and return it.  This reference
    // should be passed to nativeUnlockCanvasAndPost in place of mNativeObject,
    // because the latter could be replaced while the surface is locked.
    sp<Surface> lockedSurface(surface);
    lockedSurface->incStrong(&sRefBaseOwner);
    return (jlong) lockedSurface.get();
}

static void nativeUnlockCanvasAndPost(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject canvasObj) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));
    if (!isSurfaceValid(surface)) {
        return;
    }

    // detach the canvas from the surface
    graphics::Canvas canvas(env, canvasObj);
    canvas.setBuffer(nullptr, ADATASPACE_UNKNOWN);

    // unlock surface
    status_t err = surface->unlockAndPost();
    if (err < 0) {
        jniThrowException(env, IllegalArgumentException, NULL);
    }
}

static void nativeAllocateBuffers(JNIEnv* /* env */ , jclass /* clazz */,
        jlong nativeObject) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));
    if (!isSurfaceValid(surface)) {
        return;
    }

    surface->allocateBuffers();
}

// ----------------------------------------------------------------------------

static jlong nativeCreateFromSurfaceControl(JNIEnv* env, jclass clazz,
        jlong surfaceControlNativeObj) {
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(surfaceControlNativeObj));
    sp<Surface> surface(ctrl->createSurface());
    if (surface != NULL) {
        surface->incStrong(&sRefBaseOwner);
    }
    return reinterpret_cast<jlong>(surface.get());
}

static jlong nativeGetFromSurfaceControl(JNIEnv* env, jclass clazz,
        jlong nativeObject,
        jlong surfaceControlNativeObj) {
    Surface* self(reinterpret_cast<Surface *>(nativeObject));
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(surfaceControlNativeObj));

    // If the underlying IGBP's are the same, we don't need to do anything.
    if (self != nullptr &&
            IInterface::asBinder(self->getIGraphicBufferProducer()) ==
            IInterface::asBinder(ctrl->getIGraphicBufferProducer())) {
        return nativeObject;
    }

    sp<Surface> surface(ctrl->getSurface());
    if (surface != NULL) {
        surface->incStrong(&sRefBaseOwner);
    }

    return reinterpret_cast<jlong>(surface.get());
}

static jlong nativeReadFromParcel(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        jniThrowNullPointerException(env, NULL);
        return 0;
    }

    android::view::Surface surfaceShim;

    // Calling code in Surface.java has already read the name of the Surface
    // from the Parcel
    surfaceShim.readFromParcel(parcel, /*nameAlreadyRead*/true);

    sp<Surface> self(reinterpret_cast<Surface *>(nativeObject));

    // update the Surface only if the underlying IGraphicBufferProducer
    // has changed.
    if (self != nullptr
            && (IInterface::asBinder(self->getIGraphicBufferProducer()) ==
                    IInterface::asBinder(surfaceShim.graphicBufferProducer))) {
        // same IGraphicBufferProducer, return ourselves
        return jlong(self.get());
    }

    sp<Surface> sur;
    if (surfaceShim.graphicBufferProducer != nullptr) {
        // we have a new IGraphicBufferProducer, create a new Surface for it
        sur = new Surface(surfaceShim.graphicBufferProducer, true);
        // and keep a reference before passing to java
        sur->incStrong(&sRefBaseOwner);
    }

    if (self != NULL) {
        // and loose the java reference to ourselves
        self->decStrong(&sRefBaseOwner);
    }

    return jlong(sur.get());
}

static void nativeWriteToParcel(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    sp<Surface> self(reinterpret_cast<Surface *>(nativeObject));
    android::view::Surface surfaceShim;
    if (self != nullptr) {
        surfaceShim.graphicBufferProducer = self->getIGraphicBufferProducer();
    }
    // Calling code in Surface.java has already written the name of the Surface
    // to the Parcel
    surfaceShim.writeToParcel(parcel, /*nameAlreadyWritten*/true);
}

static jint nativeGetWidth(JNIEnv* env, jclass clazz, jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    int value = 0;
    anw->query(anw, NATIVE_WINDOW_WIDTH, &value);
    return value;
}

static jint nativeGetHeight(JNIEnv* env, jclass clazz, jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    int value = 0;
    anw->query(anw, NATIVE_WINDOW_HEIGHT, &value);
    return value;
}

static jlong nativeGetNextFrameNumber(JNIEnv *env, jclass clazz, jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    return surface->getNextFrameNumber();
}

static jboolean nativeIsBufferAccumulated(JNIEnv* env, jclass clazz,
        jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    return surface->isBufferAccumulated() ? JNI_TRUE : JNI_FALSE;
}

static void nativeSetPresentTimeMode(JNIEnv* env, jclass clazz, jlong nativeObject,
        jint mode) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    surface->setPresentTimeMode(mode);
}

static jint nativeSetScalingMode(JNIEnv *env, jclass clazz, jlong nativeObject, jint scalingMode) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    return surface->setScalingMode(scalingMode);
}

static jint nativeForceScopedDisconnect(JNIEnv *env, jclass clazz, jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    return surface->disconnect(-1, IGraphicBufferProducer::DisconnectMode::AllLocal);
}

static jint nativeAttachAndQueueBufferWithColorSpace(JNIEnv *env, jclass clazz, jlong nativeObject,
        jobject graphicBuffer, jint colorSpaceId) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    sp<GraphicBuffer> gb(android_graphics_GraphicBuffer_getNativeGraphicsBuffer(env,
                                                                                graphicBuffer));
    int err = Surface::attachAndQueueBufferWithDataspace(surface, gb,
            fromNamedColorSpaceValueToDataspace(colorSpaceId));
    return err;
}

static jint nativeSetSharedBufferModeEnabled(JNIEnv* env, jclass clazz, jlong nativeObject,
        jboolean enabled) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    return anw->perform(surface, NATIVE_WINDOW_SET_SHARED_BUFFER_MODE, int(enabled));
}

static jint nativeSetAutoRefreshEnabled(JNIEnv* env, jclass clazz, jlong nativeObject,
        jboolean enabled) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    return anw->perform(surface, NATIVE_WINDOW_SET_AUTO_REFRESH, int(enabled));
}

static jint nativeSetFrameRate(JNIEnv* env, jclass clazz, jlong nativeObject, jfloat frameRate,
                               jint compatibility) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    // Our compatibility is a Surface.FRAME_RATE_COMPATIBILITY_* value, and
    // NATIVE_WINDOW_SET_FRAME_RATE takes an
    // ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_* value. The values are identical
    // though, so no need to explicitly convert.
    return anw->perform(surface, NATIVE_WINDOW_SET_FRAME_RATE, float(frameRate), compatibility);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gSurfaceMethods[] = {
    {"nativeCreateFromSurfaceTexture", "(Landroid/graphics/SurfaceTexture;)J",
            (void*)nativeCreateFromSurfaceTexture },
    {"nativeRelease", "(J)V",
            (void*)nativeRelease },
    {"nativeIsValid", "(J)Z",
            (void*)nativeIsValid },
    {"nativeIsConsumerRunningBehind", "(J)Z",
            (void*)nativeIsConsumerRunningBehind },
    {"nativeLockCanvas", "(JLandroid/graphics/Canvas;Landroid/graphics/Rect;)J",
            (void*)nativeLockCanvas },
    {"nativeUnlockCanvasAndPost", "(JLandroid/graphics/Canvas;)V",
            (void*)nativeUnlockCanvasAndPost },
    {"nativeAllocateBuffers", "(J)V",
            (void*)nativeAllocateBuffers },
    {"nativeCreateFromSurfaceControl", "(J)J",
            (void*)nativeCreateFromSurfaceControl },
    {"nativeGetFromSurfaceControl", "(JJ)J",
            (void*)nativeGetFromSurfaceControl },
    {"nativeReadFromParcel", "(JLandroid/os/Parcel;)J",
            (void*)nativeReadFromParcel },
    {"nativeWriteToParcel", "(JLandroid/os/Parcel;)V",
            (void*)nativeWriteToParcel },
    {"nativeGetWidth", "(J)I", (void*)nativeGetWidth },
    {"nativeGetHeight", "(J)I", (void*)nativeGetHeight },
    {"nativeGetNextFrameNumber", "(J)J", (void*)nativeGetNextFrameNumber },
    {"nativeIsBufferAccumulated", "(J)Z", (void*)nativeIsBufferAccumulated },
    {"nativeSetPresentTimeMode", "(JI)V", (void*)nativeSetPresentTimeMode },
    {"nativeSetScalingMode", "(JI)I", (void*)nativeSetScalingMode },
    {"nativeForceScopedDisconnect", "(J)I", (void*)nativeForceScopedDisconnect},
    {"nativeAttachAndQueueBufferWithColorSpace", "(JLandroid/graphics/GraphicBuffer;I)I",
            (void*)nativeAttachAndQueueBufferWithColorSpace},
    {"nativeSetSharedBufferModeEnabled", "(JZ)I", (void*)nativeSetSharedBufferModeEnabled},
    {"nativeSetAutoRefreshEnabled", "(JZ)I", (void*)nativeSetAutoRefreshEnabled},
    {"nativeSetFrameRate", "(JFI)I", (void*)nativeSetFrameRate},
};

int register_android_view_Surface(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/view/Surface",
            gSurfaceMethods, NELEM(gSurfaceMethods));

    jclass clazz = FindClassOrDie(env, "android/view/Surface");
    gSurfaceClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gSurfaceClassInfo.mNativeObject = GetFieldIDOrDie(env,
            gSurfaceClassInfo.clazz, "mNativeObject", "J");
    gSurfaceClassInfo.mLock = GetFieldIDOrDie(env,
            gSurfaceClassInfo.clazz, "mLock", "Ljava/lang/Object;");
    gSurfaceClassInfo.ctor = GetMethodIDOrDie(env, gSurfaceClassInfo.clazz, "<init>", "(J)V");

    clazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.left = GetFieldIDOrDie(env, clazz, "left", "I");
    gRectClassInfo.top = GetFieldIDOrDie(env, clazz, "top", "I");
    gRectClassInfo.right = GetFieldIDOrDie(env, clazz, "right", "I");
    gRectClassInfo.bottom = GetFieldIDOrDie(env, clazz, "bottom", "I");

    return err;
}

};
