cmake_minimum_required(VERSION 3.22.1)
project(fakelocation_native_runtime C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_ANDROID_STL_TYPE c++_static)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

add_executable(inj64 src/inj64.cpp)
target_link_libraries(inj64 dl log)

add_library(fl_init64 SHARED src/libfl_init64.cpp)
set_target_properties(fl_init64 PROPERTIES OUTPUT_NAME fl_init64)
target_link_libraries(fl_init64 dl log)

add_library(fl_app64 SHARED src/libfl_app64.cpp)
set_target_properties(fl_app64 PROPERTIES OUTPUT_NAME fl_app64)
target_link_libraries(fl_app64 dl log)

add_library(lh64 SHARED src/liblh64.cpp)
set_target_properties(lh64 PROPERTIES OUTPUT_NAME lh64)
target_link_libraries(lh64 dl log)
