#define LOG_TAG "Variometer-jni"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <utils/misc.h>
#include <utils/Log.h>
#include <sys/ioctl.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>

#define DEV_VARIOMETER  "/dev/altimeter"
#define DISPLAY_CLASS   "com/fourtech/hardware/Variometer"

namespace android {

static int sFd;
static Mutex sMutex;

#define IOCTL_GET_PRES_TEMP  _IOR('A', 0x01, struct altimeter_t)
#define IOCTL_GET_PRES       _IOR('A', 0x02, int32_t)
#define IOCTL_GET_TEMP       _IOR('A', 0x03, int32_t)

struct altimeter_t {
	int32_t pressure;
	int32_t temperature;
};

static jint Variometer_open(JNIEnv *env, jclass clazz) {
	Mutex::Autolock autoLock(sMutex);
	ALOGI("Variometer_open");
	sFd = open(DEV_VARIOMETER, O_RDONLY);
	if (sFd < 0) {
		ALOGE("Unable to open  %s", DEV_VARIOMETER);
		return 0;
	}
	return 1;
}

static jint Variometer_close(JNIEnv *env, jclass clazz) {
	Mutex::Autolock autoLock(sMutex);
	ALOGI("Variometer_close");
	if (sFd) close(sFd);
	sFd = -1;
	return 1;
}

static jint Variometer_getPressure(JNIEnv *env, jclass clazz) {
	Mutex::Autolock autoLock(sMutex);
	int32_t pres = 0;
	if (ioctl(sFd, IOCTL_GET_PRES, &pres) < 1) {
		ALOGE("IOCTL_GET_PRES error");
		return 0;
	}
	return pres;
}

static jint Variometer_getTemperature(JNIEnv *env, jclass clazz) {
	Mutex::Autolock autoLock(sMutex);
	int32_t temp = 0;
	if (ioctl(sFd, IOCTL_GET_TEMP, &temp) < 1) {
		ALOGE("IOCTL_GET_TEMP error");
		return 0;
	}
	return temp;
}

static void Variometer_getValues(JNIEnv *env, jclass clazz,
		jintArray outValues) {
	Mutex::Autolock autoLock(sMutex);
	struct altimeter_t at;
	if (ioctl(sFd, IOCTL_GET_PRES_TEMP, &at) < 1) {
		ALOGE("IOCTL_GET_PRES_TEMP error");
		return;
	}

	jint* values = env->GetIntArrayElements(outValues, 0);
	values[0] = at.pressure;
	values[1] = at.temperature;
	env->ReleaseIntArrayElements(outValues, values, 0);
}

static JNINativeMethod method_table[] = {
		{ "open",            "()V",    (void*) Variometer_open },
		{ "close",           "()V",    (void*) Variometer_close },
		{ "getValues",       "([I)V",  (void*) Variometer_getValues },
		{ "getPressure",     "()I",    (void*) Variometer_getPressure },
		{ "getTemperature",  "()I",    (void*) Variometer_getTemperature },
};

int register_fourtech_hardware_Variometer(JNIEnv *env) {
	return jniRegisterNativeMethods(env, DISPLAY_CLASS, method_table, NELEM(method_table));
}

}
