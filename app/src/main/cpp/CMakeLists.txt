cmake_minimum_required(VERSION 3.10.2)
project("CarBuddyNative")
set(CMAKE_ANDROID_ARCH_ABI "arm64-v8a;armeabi-v7a;x86;x86_64" CACHE STRING "Android ABI")

# Add Oboe as a subdirectory
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../../../../oboe" oboe-bin)

# Add source files for the native library, including KissFFT
add_library(native-lib SHARED
        AudioEngine.cpp
        kissfft/kiss_fft.c
        kissfft/kiss_fftr.c
)

# Include directories for Oboe and KissFFT
target_include_directories(native-lib PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/../../../../oboe/include"
        "${CMAKE_CURRENT_LIST_DIR}/kissfft"
)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)

# Link libraries (no additional linking needed for KissFFT as it's header-only/source-based)
target_link_libraries(native-lib
        oboe
        ${log-lib}
        ${android-lib}
)

# Optional: Set C++ standard if needed
target_compile_features(native-lib PUBLIC cxx_std_17)