cmake_minimum_required(VERSION 3.21)
project(souz_llama_bridge C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_WEBUI OFF CACHE BOOL "" FORCE)
set(GGML_METAL ON CACHE BOOL "" FORCE)

set(
    LLAMA_CPP_SOURCE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/llama.cpp"
    CACHE PATH
    "Path to a llama.cpp source checkout"
)
get_filename_component(LLAMA_CPP_SOURCE_DIR "${LLAMA_CPP_SOURCE_DIR}" ABSOLUTE)

if(NOT EXISTS "${LLAMA_CPP_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_CPP_SOURCE_DIR must point to a llama.cpp checkout: ${LLAMA_CPP_SOURCE_DIR}")
endif()

add_subdirectory("${LLAMA_CPP_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(souz_llama_bridge SHARED
    include/souz_llama_bridge.h
    src/souz_llama_bridge.cpp
)

target_include_directories(souz_llama_bridge
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${LLAMA_CPP_SOURCE_DIR}/include
        ${LLAMA_CPP_SOURCE_DIR}/ggml/include
        ${LLAMA_CPP_SOURCE_DIR}/vendor
)

target_link_libraries(souz_llama_bridge PRIVATE llama)

set_target_properties(souz_llama_bridge PROPERTIES
    OUTPUT_NAME "souz_llama_bridge"
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin
)
