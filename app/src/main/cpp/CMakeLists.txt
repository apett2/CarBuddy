cmake_minimum_required(VERSION 3.10.2)
project("CarBuddyNative")

# Add Oboe as a subdirectory
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../../../../oboe" oboe-bin)

# Add source files for the native library, including KissFFT and native-lib.cpp
add_library(native-lib SHARED
        AudioEngine.cpp
        kissfft/kiss_fft.c
        kissfft/kiss_fftr.c
)

# Include directories for Oboe and KissFFT
target_include_directories(native-lib PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/../../../oboe/include"
        "${CMAKE_CURRENT_LIST_DIR}/kissfft"
)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)

# Link libraries
target_link_libraries(native-lib
        oboe
        ${log-lib}
        ${android-lib}
)

# Set C++ standard
target_compile_features(native-lib PUBLIC cxx_std_17)