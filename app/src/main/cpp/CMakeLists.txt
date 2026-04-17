cmake_minimum_required(VERSION 3.22.1)

project("c47")

# ---------------------------------------------------------------------------
# Paths into the vendored upstream engine tree
# ---------------------------------------------------------------------------
set(C47_ROOT      ${CMAKE_CURRENT_SOURCE_DIR}/../../../../c43-source)
set(C47_CORE      ${C47_ROOT}/src/c47)
set(C47_ANDROID   ${C47_ROOT}/src/c47-android)
set(C47_GENERATED ${C47_ROOT}/src/generated)
set(DECNUMBER_DIR ${C47_ROOT}/dep/decNumberICU)

# ---------------------------------------------------------------------------
# Platform flag: ANDROID_BUILD takes the place of PC_BUILD / DMCP_BUILD.
# Applied globally so whatever we compile from the engine tree sees it.
# ---------------------------------------------------------------------------
add_compile_definitions(ANDROID_BUILD)

# Engine reads CALCMODEL to pick the R47 vs C47 personality. Match the app.
add_compile_definitions(CALCMODEL=USER_R47)

# ---------------------------------------------------------------------------
# Phase 1 MVP: JNI-only shared library.
#
# The full engine needs GMP cross-compiled for Android and the host-generated
# headers in src/generated/ — both are deferred. For now libc47.so contains
# just the JNI entry points so we can verify:
#   1. the NDK toolchain builds for all four ABIs
#   2. System.loadLibrary("c47") succeeds at runtime
#   3. the JNI surface declared in the plan is callable from Kotlin
# ---------------------------------------------------------------------------
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${C47_CORE}
    ${C47_CORE}/hal
    ${C47_GENERATED}
    ${DECNUMBER_DIR}
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
)

# ---------------------------------------------------------------------------
# Future work (see INTERACTIVITY_PLAN.md Phase 1):
#
#   - Cross-compile GMP 6.2.1 for arm64-v8a, armeabi-v7a, x86_64, x86.
#     Add as an ExternalProject or check in prebuilt .a files under
#     ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libgmp.a.
#
#   - Run the host tools (generateCatalogs, generateConstants,
#     generateTestPgms) once on the dev machine, commit the outputs to
#     ${C47_GENERATED}. The generated headers depend on GMP so they have
#     to be produced from a normal desktop meson build first.
#
#   - Once both exist, uncomment and extend the block below to add the
#     ~200 engine sources + the Android HAL. The list should mirror
#     c43-source/src/c47/meson.build.
#
# file(GLOB C47_CORE_SRC
#     ${C47_CORE}/*.c
#     ${C47_CORE}/browsers/*.c
#     ${C47_CORE}/c47Extensions/*.c
#     ${C47_CORE}/core/*.c
#     ${C47_CORE}/distributions/*.c
#     ${C47_CORE}/logicalOps/*.c
#     ${C47_CORE}/mathematics/*.c
#     ${C47_CORE}/programming/*.c
#     ${C47_CORE}/solver/*.c
#     ${C47_CORE}/ui/*.c
# )
# file(GLOB C47_HAL_SRC ${C47_ANDROID}/hal/*.c)
# file(GLOB DECNUMBER_SRC ${DECNUMBER_DIR}/*.c)
#
# target_sources(${CMAKE_PROJECT_NAME} PRIVATE
#     ${C47_CORE_SRC}
#     ${C47_HAL_SRC}
#     ${DECNUMBER_SRC}
# )
# target_link_libraries(${CMAKE_PROJECT_NAME} gmp)
# ---------------------------------------------------------------------------
