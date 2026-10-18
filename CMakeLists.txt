cmake_minimum_required(VERSION 3.20)
project(AirPulse LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(airpulse-phone/entry/src/main/cpp)

add_executable(airpulse_tests tests/test_core.cpp)
target_link_libraries(airpulse_tests PRIVATE airpulse_core)

enable_testing()
add_test(
    NAME make_fixtures
    COMMAND python "${CMAKE_SOURCE_DIR}/tests/make_fixtures.py" "${CMAKE_BINARY_DIR}/fixtures"
)
add_test(
    NAME core
    COMMAND airpulse_tests "${CMAKE_BINARY_DIR}/fixtures"
)
set_tests_properties(make_fixtures PROPERTIES FIXTURES_SETUP demo_data)
set_tests_properties(core PROPERTIES FIXTURES_REQUIRED demo_data)
