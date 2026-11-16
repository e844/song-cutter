cmake_minimum_required(VERSION 3.22.1)
project(songcutter C)

# LAME (מקודד MP3) – קוד המקור נמצא ב-lame/, ו-config.h נכתב ידנית במקום autotools
file(GLOB LAME_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lame/*.c)
add_library(lamejni SHARED ${LAME_SOURCES} lame_jni.c)
target_include_directories(lamejni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/lame)
target_compile_definitions(lamejni PRIVATE HAVE_CONFIG_H)
target_compile_options(lamejni PRIVATE -O2 -w)
target_link_libraries(lamejni m)
