cmake_minimum_required(VERSION 3.22.1)
project("local_bloxd_native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(local_bloxd_native SHARED
    local_bloxd_native.cpp
)

target_compile_options(local_bloxd_native PRIVATE
    -O3
    -fvisibility=hidden
    -Wall
    -Wextra
)

target_link_options(local_bloxd_native PRIVATE
    "-Wl,-z,max-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(local_bloxd_native ${log-lib})
