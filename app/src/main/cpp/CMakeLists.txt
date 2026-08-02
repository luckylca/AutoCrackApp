cmake_minimum_required(VERSION 3.22.1)

project(autocrack_pty LANGUAGES CXX)

add_library(
    autocrack_pty
    SHARED
    native_pty.cpp
)

target_compile_features(autocrack_pty PRIVATE cxx_std_20)
target_compile_options(
    autocrack_pty
    PRIVATE
    -Wall
    -Wextra
    -Werror
    -Wconversion
    -Wsign-conversion
    -fno-exceptions
    -fno-rtti
)

target_link_libraries(autocrack_pty PRIVATE log)
