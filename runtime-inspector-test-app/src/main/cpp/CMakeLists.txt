cmake_minimum_required(VERSION 3.22.1)
project(autocrack_runtime_test_native LANGUAGES CXX)

add_library(autocrack_jnitrace_probe SHARED jnitrace_probe.cpp)

target_compile_features(autocrack_jnitrace_probe PRIVATE cxx_std_17)
target_compile_options(autocrack_jnitrace_probe PRIVATE -Wall -Wextra -Werror)
