cmake_minimum_required(VERSION 3.22)
project(js8sdr LANGUAGES C CXX)

# SDR receive (issue #54). libusb (LGPL-2.1+), librtlsdr (osmocom, GPL-2.0+) and libairspyhf
# (Airspy, BSD-3-Clause) are vendored as submodules under <repo>/vendor and built static, here.
set(REPO ${CMAKE_CURRENT_LIST_DIR}/../../../../..)
set(LIBUSB ${REPO}/vendor/libusb)
set(RTLSDR ${REPO}/vendor/rtl-sdr)
set(AIRSPYHF ${REPO}/vendor/airspyhf/libairspyhf/src)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(usb1 STATIC
    ${LIBUSB}/libusb/core.c
    ${LIBUSB}/libusb/descriptor.c
    ${LIBUSB}/libusb/hotplug.c
    ${LIBUSB}/libusb/io.c
    ${LIBUSB}/libusb/sync.c
    ${LIBUSB}/libusb/strerror.c
    ${LIBUSB}/libusb/os/linux_usbfs.c
    ${LIBUSB}/libusb/os/events_posix.c
    ${LIBUSB}/libusb/os/threads_posix.c
    ${LIBUSB}/libusb/os/linux_netlink.c)
target_include_directories(usb1 PRIVATE ${LIBUSB}/android ${LIBUSB}/libusb/os PUBLIC ${LIBUSB}/libusb)
target_compile_options(usb1 PRIVATE -fvisibility=hidden -pthread -Wno-unused-parameter)

# librtlsdr.c is compiled inside rtlsdr_fd.c (see there); the tuners alongside it.
add_library(rtlsdr STATIC
    rtlsdr_fd.c
    ${RTLSDR}/src/tuner_e4k.c
    ${RTLSDR}/src/tuner_fc0012.c
    ${RTLSDR}/src/tuner_fc0013.c
    ${RTLSDR}/src/tuner_fc2580.c
    ${RTLSDR}/src/tuner_r82xx.c)
target_include_directories(rtlsdr PRIVATE ${RTLSDR}/src PUBLIC ${RTLSDR}/include)
target_compile_definitions(rtlsdr PUBLIC rtlsdr_STATIC)
target_compile_options(rtlsdr PRIVATE -Wno-unused-parameter -Wno-sign-compare -Wno-implicit-function-declaration)
target_link_libraries(rtlsdr PUBLIC usb1 log)

# libairspyhf, unmodified: it opens from an Android file descriptor itself (airspyhf_open_fd).
add_library(airspyhf STATIC ${AIRSPYHF}/airspyhf.c ${AIRSPYHF}/iqbalancer.c)
target_include_directories(airspyhf PUBLIC ${AIRSPYHF})
target_compile_options(airspyhf PRIVATE -Wno-unused-parameter -Wno-sign-compare -Wno-unused-variable)
target_link_libraries(airspyhf PUBLIC usb1 m log)

add_library(js8sdr SHARED sdr_jni.cpp airspy_jni.cpp)
target_link_libraries(js8sdr rtlsdr airspyhf android log)
