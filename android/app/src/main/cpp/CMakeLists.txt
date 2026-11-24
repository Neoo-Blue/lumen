cmake_minimum_required(VERSION 3.22.1)
project(lumen_node_jni)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(node-jni SHARED node-jni.cpp)

# android/nodejs/include is produced by nodejs/fetch.sh.
# Public headers live under include/node (node.h, v8.h, node_api.h).
set(NODEJS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../nodejs")
target_include_directories(node-jni PRIVATE "${NODEJS_ROOT}/include/node")

set(NODE_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")
if(NOT EXISTS "${NODE_LIB}")
  message(FATAL_ERROR "Missing ${NODE_LIB}. Run nodejs/fetch.sh before assembling.")
endif()

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
  IMPORTED_LOCATION "${NODE_LIB}"
  INTERFACE_INCLUDE_DIRECTORIES "${NODEJS_ROOT}/include/node")

find_library(log-lib log REQUIRED)
target_link_libraries(node-jni PRIVATE node ${log-lib})

# 16 KB ELF alignment. max-page-size is required; common-page-size keeps
# RELRO from sharing a trailing page with writable data on NDK r27.
target_link_options(node-jni PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384")
