LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_MODULE := libaltimeter_jni

LOCAL_SRC_FILES:= \
    onload.cpp \
    com_fourtech_hardware_Variometer.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libnativehelper

ifeq ($(WITH_MALLOC_LEAK_CHECK),true)
    LOCAL_CFLAGS += -DMALLOC_LEAK_CHECK
endif

include $(BUILD_SHARED_LIBRARY)
