cmake_minimum_required(VERSION 3.22.1)
project(airpulse_android)

# Same portable core the desktop tests build. Paths stay inside this repo.
set(AIRPULSE_CORE ${CMAKE_CURRENT_LIST_DIR}/../../../../../airpulse-phone/entry/src/main/cpp)

add_library(airpulse SHARED
    jni_engine.cpp
    ${AIRPULSE_CORE}/src/image_ops.cpp
    ${AIRPULSE_CORE}/src/signal.cpp
    ${AIRPULSE_CORE}/src/farneback.cpp
    ${AIRPULSE_CORE}/src/evm_pulse.cpp
    ${AIRPULSE_CORE}/src/bos_flow.cpp
)

target_include_directories(airpulse PRIVATE ${AIRPULSE_CORE}/include)
target_compile_features(airpulse PRIVATE cxx_std_17)
target_compile_options(airpulse PRIVATE -O3 -fexceptions -frtti)

find_library(log-lib log)
target_link_libraries(airpulse ${log-lib})
