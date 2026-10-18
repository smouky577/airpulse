# Portable AirPulse core (C++17, no HarmonyOS headers).
# Desktop tests build this as a static library. The HarmonyOS NAPI wrapper
# is a later milestone: Camera Kit is not guessed here.

cmake_minimum_required(VERSION 3.20)

set(AIRPULSE_CORE_SOURCES
    src/image_ops.cpp
    src/signal.cpp
    src/farneback.cpp
    src/evm_pulse.cpp
    src/bos_flow.cpp
)

if(NOT TARGET airpulse_core)
    add_library(airpulse_core STATIC ${AIRPULSE_CORE_SOURCES})
    target_include_directories(airpulse_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
endif()
