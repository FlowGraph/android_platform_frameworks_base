LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	flowgdmp.cpp

LOCAL_SHARED_LIBRARIES := libutils libbinder

ifeq ($(TARGET_OS),linux)
	LOCAL_CFLAGS += -DXP_UNIX
	#LOCAL_SHARED_LIBRARIES += librt
endif

LOCAL_MODULE:= flowgdmp

include $(BUILD_EXECUTABLE)
