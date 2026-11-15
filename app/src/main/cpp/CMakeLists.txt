cmake_minimum_required(VERSION 3.22.1)
project("fable_fast" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(fable_fast SHARED
    fable_fast.cpp
)

target_compile_options(fable_fast PRIVATE
    -O3
    -Wall
    -Wextra
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
)

target_link_options(fable_fast PRIVATE
    -Wl,-z,max-page-size=16384
)

find_library(log-lib log)
target_link_libraries(fable_fast ${log-lib} dl)
