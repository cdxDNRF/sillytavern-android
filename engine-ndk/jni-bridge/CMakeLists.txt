cmake_minimum_required(VERSION 3.22.1)
project(st_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Location of unpacked prebuilt Node.js engine archive (include/ + lib/)
set(NODE_PREBUILT_DIR "${CMAKE_CURRENT_LIST_DIR}/../prebuilt" CACHE PATH "Path to unpacked libnode prebuilt directory")

set(NODE_INCLUDE_DIR "${NODE_PREBUILT_DIR}/include/node")
set(NODE_LIB_PATH "${NODE_PREBUILT_DIR}/lib/${ANDROID_ABI}/libnode.so")

if(NOT EXISTS "${NODE_INCLUDE_DIR}/node.h")
    message(FATAL_ERROR "Missing Node headers at ${NODE_INCLUDE_DIR}/node.h. Run engine-ndk/scripts/build-libnode.sh or unpack libnode-android-arm64.tar.gz into engine-ndk/prebuilt.")
endif()
if(NOT EXISTS "${NODE_LIB_PATH}")
    message(FATAL_ERROR "Missing libnode.so at ${NODE_LIB_PATH}. Run engine-ndk/scripts/build-libnode.sh or unpack libnode-android-arm64.tar.gz into engine-ndk/prebuilt.")
endif()

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION "${NODE_LIB_PATH}"
)

add_library(st_bridge SHARED
    st_bridge.cpp
)

target_include_directories(st_bridge PRIVATE
    "${NODE_INCLUDE_DIR}"
)

target_compile_options(st_bridge PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
)

# Enforce 16 KB ELF page alignment required on modern Android 15+ devices.
target_link_options(st_bridge PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(st_bridge
    node
    ${log-lib}
    ${dl-lib}
)
