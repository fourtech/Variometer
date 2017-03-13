#define LOG_TAG "Variometer-jni-loader"

#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

namespace android {
int register_fourtech_hardware_Variometer(JNIEnv *env);
}

using namespace android;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
	JNIEnv* env = NULL;
	jint result = -1;

	if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
		ALOGE("GetEnv failed!");
		return result;
	}

	register_fourtech_hardware_Variometer(env);

	return JNI_VERSION_1_4;
}
