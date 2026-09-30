LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := androidjtools_live
LOCAL_SRC_FILES := live_aaudio.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
LOCAL_LDLIBS := -laaudio -llog -latomic
include $(BUILD_SHARED_LIBRARY)
