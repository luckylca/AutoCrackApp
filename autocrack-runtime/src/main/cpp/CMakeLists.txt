cmake_minimum_required(VERSION 3.22.1)
project(autocrack_runtime_native LANGUAGES C CXX)

add_library(autocrack_runtime_native SHARED native_bridge.cpp)

target_compile_features(autocrack_runtime_native PRIVATE cxx_std_17)
target_compile_options(autocrack_runtime_native PRIVATE -Wall -Wextra -Werror)
target_link_libraries(autocrack_runtime_native PRIVATE log dl)
