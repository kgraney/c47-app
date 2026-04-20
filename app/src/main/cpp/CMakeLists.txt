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
set(GMP_DIR       ${CMAKE_CURRENT_SOURCE_DIR}/third_party/gmp)

# ---------------------------------------------------------------------------
# Platform flag: ANDROID_BUILD takes the place of PC_BUILD / DMCP_BUILD.
# CALCMODEL picks the R47 personality.
# ---------------------------------------------------------------------------
add_compile_definitions(ANDROID_BUILD)
add_compile_definitions(CALCMODEL=USER_R47)
add_compile_definitions(C47_ENGINE_LINKED)

# Engine requires one of OS32BIT / OS64BIT to pick long-integer limb width.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    add_compile_definitions(OS64BIT)
else()
    add_compile_definitions(OS32BIT)
endif()

# Silence engine warnings that the upstream build treats as -Wno. The engine
# was written for stricter PC toolchains; NDK clang trips on signedness and
# unused-parameter noise that the meson build masks with -Wno-* globally.
add_compile_options(
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-unused-but-set-variable
    -Wno-sign-compare
    -Wno-missing-field-initializers
    -Wno-implicit-function-declaration
    -Wno-incompatible-pointer-types
    -Wno-int-conversion
    -Wno-constant-conversion
    -Wno-pointer-sign
)

# Release-only optimization. We ship arm64-v8a only, so clang's default tune
# (-march=armv8-a) is already appropriate. Deliberately no -ffast-math —
# engine IEEE paths want strict semantics.
add_compile_options(
    $<$<CONFIG:Release>:-O3>
    $<$<CONFIG:Release>:-flto=thin>
    $<$<CONFIG:Release>:-fvisibility=hidden>
    $<$<CONFIG:Release>:-ffunction-sections>
    $<$<CONFIG:Release>:-fdata-sections>
)
add_link_options(
    $<$<CONFIG:Release>:-flto=thin>
    $<$<CONFIG:Release>:-Wl,--gc-sections>
    $<$<CONFIG:Release>:-Wl,--icf=safe>
)

# ---------------------------------------------------------------------------
# GMP: per-ABI prebuilt static libs (see scripts/build-gmp-android.sh).
# ---------------------------------------------------------------------------
add_library(gmp STATIC IMPORTED)
set_target_properties(gmp PROPERTIES
    IMPORTED_LOCATION             ${GMP_DIR}/lib/${ANDROID_ABI}/libgmp.a
    INTERFACE_INCLUDE_DIRECTORIES ${GMP_DIR}/include/${ANDROID_ABI}
)

# ---------------------------------------------------------------------------
# decNumber (vendored under c43-source/dep/decNumberICU)
# ---------------------------------------------------------------------------
set(DECNUMBER_SRC
    ${DECNUMBER_DIR}/decContext.c
    ${DECNUMBER_DIR}/decDouble.c
    ${DECNUMBER_DIR}/decimal128.c
    ${DECNUMBER_DIR}/decimal64.c
    ${DECNUMBER_DIR}/decNumber.c
    ${DECNUMBER_DIR}/decQuad.c
)

# ---------------------------------------------------------------------------
# C47 engine sources (mirrors c43-source/src/c47/meson.build c47_src list)
# ---------------------------------------------------------------------------
file(GLOB C47_CORE_TOPLEVEL LIST_DIRECTORIES false ${C47_CORE}/*.c)
# reservedRegisterLookupGenerator.c is a build-time tool, not part of the lib.
list(FILTER C47_CORE_TOPLEVEL EXCLUDE REGEX "reservedRegisterLookupGenerator\\.c$")

file(GLOB C47_BROWSERS      ${C47_CORE}/browsers/*.c)
file(GLOB C47_C47EXT        ${C47_CORE}/c47Extensions/*.c)
file(GLOB C47_CORE_SUB      ${C47_CORE}/core/*.c)
file(GLOB C47_DISTRIBUTIONS ${C47_CORE}/distributions/*.c)
file(GLOB C47_LOGICALOPS    ${C47_CORE}/logicalOps/*.c)
file(GLOB C47_MATHEMATICS   ${C47_CORE}/mathematics/*.c)
file(GLOB C47_PROGRAMMING   ${C47_CORE}/programming/*.c)
file(GLOB C47_SOLVER        ${C47_CORE}/solver/*.c)
file(GLOB C47_UI            ${C47_CORE}/ui/*.c)

set(C47_SRC
    ${C47_CORE_TOPLEVEL}
    ${C47_BROWSERS}
    ${C47_C47EXT}
    ${C47_CORE_SUB}
    ${C47_DISTRIBUTIONS}
    ${C47_LOGICALOPS}
    ${C47_MATHEMATICS}
    ${C47_PROGRAMMING}
    ${C47_SOLVER}
    ${C47_UI}
)

# Host-generated sources (produced by scripts/copy-generated.sh)
set(C47_GENERATED_SRC
    ${C47_GENERATED}/constantPointers.c
    ${C47_GENERATED}/rasterFontsData.c
)

# Android HAL
file(GLOB C47_HAL_SRC ${C47_ANDROID}/hal/*.c)

# ---------------------------------------------------------------------------
# libc47.so: JNI bridge + engine + decNumber
# ---------------------------------------------------------------------------
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    ${C47_SRC}
    ${C47_GENERATED_SRC}
    ${C47_HAL_SRC}
    ${DECNUMBER_SRC}
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${C47_CORE}
    ${C47_CORE}/hal
    ${C47_GENERATED}
    ${DECNUMBER_DIR}
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    gmp
    android
    log
)
