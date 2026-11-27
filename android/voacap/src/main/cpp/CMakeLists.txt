cmake_minimum_required(VERSION 3.22)
project(js8voacap LANGUAGES C CXX)

# VOACAP for the Prop tab (issue #57): the hfcast Rust port (Apache-2.0, vendored at
# <repo>/vendor/hfcast-engine), behind the small C ABI in ../../../rust, built here as a static
# library with the toolchain pinned in rust/rust-toolchain.toml and linked into one JNI library.
set(RUST_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../rust)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET armv7-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET x86_64-linux-android)
else()
    message(FATAL_ERROR "No Rust target for ABI ${ANDROID_ABI}")
endif()

# -Pjs8.voacapStub=true (development machines without the Rust Android targets): build the JNI
# library with no engine behind it; Voacap.predict then fails with a clear message. CI never
# sets it, so no published APK can carry the stub.
option(JS8_VOACAP_STUB "Build without the Rust engine" OFF)
if(JS8_VOACAP_STUB)
    message(WARNING "js8voacap: building the stub; VOACAP predictions will be unavailable")
    add_library(js8voacap SHARED voacap_jni.cpp)
    target_compile_definitions(js8voacap PRIVATE JS8_VOACAP_STUB=1)
    target_link_libraries(js8voacap PRIVATE log)
    return()
endif()

find_program(CARGO cargo HINTS $ENV{CARGO_HOME}/bin $ENV{HOME}/.cargo/bin REQUIRED)
set(CARGO_TARGET_DIR ${CMAKE_BINARY_DIR}/cargo)
set(RUST_LIB ${CARGO_TARGET_DIR}/${RUST_TARGET}/release/libjs8voacap.a)

# --locked --offline: the crate and hfcast have no dependencies from any registry, so the build
# reads nothing from the network and the lock file can't move.
add_custom_command(
    OUTPUT ${RUST_LIB}
    COMMAND ${CMAKE_COMMAND} -E env CARGO_TARGET_DIR=${CARGO_TARGET_DIR}
            ${CARGO} build --release --locked --offline --target ${RUST_TARGET}
            --manifest-path ${RUST_DIR}/Cargo.toml
    WORKING_DIRECTORY ${RUST_DIR}
    DEPENDS ${RUST_DIR}/src/lib.rs ${RUST_DIR}/Cargo.toml ${RUST_DIR}/Cargo.lock
            ${RUST_DIR}/rust-toolchain.toml
    COMMENT "cargo build js8voacap for ${RUST_TARGET}"
    USES_TERMINAL
    VERBATIM)
add_custom_target(js8voacap_cargo DEPENDS ${RUST_LIB})

add_library(js8voacap_rs STATIC IMPORTED)
set_target_properties(js8voacap_rs PROPERTIES IMPORTED_LOCATION ${RUST_LIB})
add_dependencies(js8voacap_rs js8voacap_cargo)

add_library(js8voacap SHARED voacap_jni.cpp)
set_target_properties(js8voacap PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(js8voacap PRIVATE -Wall -Wextra -fvisibility=hidden)
# Only the JNI entry points are exported; the Rust symbols stay inside.
target_link_options(js8voacap PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
# Rust's std needs these at link time (a static library carries no link directives).
target_link_libraries(js8voacap PRIVATE js8voacap_rs log dl m unwind)
